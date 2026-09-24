defmodule Dispatch.Access.CapabilitySigner do
  @moduledoc """
  Signs the capability document the field application enables features from
  (Section 23.2, ADR-0009).

  The format is a JWS compact serialization with `ES256` (RFC 7515, RFC 7518
  §3.4). Two properties decided that:

  - **No canonicalization.** The signature covers the exact base64url bytes of
    the payload, so the client verifies what it received rather than a
    re-serialization of it. A canonical-JSON scheme would make both sides agree
    on key order, number formatting and escaping, and a disagreement there reads
    as a forged document.
  - **Verifiable at `minSdk 29`.** P-256 ECDSA is available on every supported
    Android release through `java.security`; Ed25519 is not until API 33.

  The key is `CAPABILITY_SIGNING_KEY`, a PKCS#8 PEM loaded at runtime like
  every other secret and never compiled into a release (Section 31).
  """

  @typedoc "A loaded signing key: the private key and its public key identifier."
  @type key :: %{private: tuple(), kid: String.t()}

  @header_alg "ES256"
  @coordinate_bytes 32

  @doc """
  Signs `claims` as a compact JWS.

  `claims` is encoded with `Jason`; the header names the algorithm, the key
  identifier, and a `typ` that stops this token being accepted anywhere a
  different JWS is expected.
  """
  @spec sign(map(), key()) :: String.t()
  def sign(claims, %{private: private, kid: kid}) when is_map(claims) do
    header = encode_segment(%{"alg" => @header_alg, "kid" => kid, "typ" => "capability+jws"})
    payload = encode_segment(claims)
    input = header <> "." <> payload

    signature =
      input
      |> :public_key.sign(:sha256, private)
      |> der_to_raw()
      |> Base.url_encode64(padding: false)

    input <> "." <> signature
  end

  @doc """
  Verifies a compact JWS against `public` and returns its claims.

  The server never needs to verify its own documents in production; this exists
  so the tests exercise the same verification the client performs, rather than
  asserting on a token nobody checked.
  """
  @spec verify(String.t(), tuple()) :: {:ok, map()} | {:error, :invalid_signature}
  def verify(token, public) when is_binary(token) do
    with [header, payload, signature] <- String.split(token, "."),
         {:ok, %{"alg" => @header_alg}} <- decode_segment(header),
         {:ok, raw} <- Base.url_decode64(signature, padding: false),
         true <- :public_key.verify(header <> "." <> payload, :sha256, raw_to_der(raw), public),
         {:ok, claims} <- decode_segment(payload) do
      {:ok, claims}
    else
      _invalid -> {:error, :invalid_signature}
    end
  end

  @doc """
  The configured signing key, or an error naming why it is unusable.

  Decoded on each call rather than cached: signing happens once per capability
  refresh, which is rare, so there is nothing to save and one less copy of the
  private key to reason about.
  """
  @spec key() :: {:ok, key()} | {:error, :unconfigured | :unreadable}
  def key do
    case Application.get_env(:dispatch, :capability_signing_key) do
      pem when is_binary(pem) and pem != "" -> decode(pem)
      _unset -> {:error, :unconfigured}
    end
  end

  @doc "Decodes a PKCS#8 P-256 private key from PEM."
  @spec decode(String.t()) :: {:ok, key()} | {:error, :unreadable}
  def decode(pem) when is_binary(pem) do
    with [entry | _rest] <- :public_key.pem_decode(pem),
         {:ECPrivateKey, _v, _d, {:namedCurve, curve}, public, _attrs} = private <-
           :public_key.pem_entry_decode(entry),
         true <- curve == :pubkey_cert_records.namedCurves(:secp256r1) do
      {:ok, %{private: private, kid: kid({{:ECPoint, public}, {:namedCurve, curve}})}}
    else
      _unusable -> {:error, :unreadable}
    end
  rescue
    # `pem_entry_decode/1` raises on a structurally valid PEM whose body is not
    # a key. Malformed configuration is a value here, not a crash.
    _malformed -> {:error, :unreadable}
  end

  @doc """
  The public half of a loaded key, in the form `:public_key.verify/4` takes.
  """
  @spec public(key()) :: tuple()
  def public(%{private: {:ECPrivateKey, _v, _d, params, point, _attrs}}),
    do: {{:ECPoint, point}, params}

  @doc """
  The key identifier: the first 16 base64url characters of the SHA-256 of the
  DER SubjectPublicKeyInfo.

  Derived rather than configured, so a rotated key cannot keep an old `kid`,
  and the client can compute the same value from the public key it pins.
  """
  @spec kid(tuple()) :: String.t()
  def kid(public) do
    {:SubjectPublicKeyInfo, spki, :not_encrypted} =
      :public_key.pem_entry_encode(:SubjectPublicKeyInfo, public)

    :sha256
    |> :crypto.hash(spki)
    |> Base.url_encode64(padding: false)
    |> binary_part(0, 16)
  end

  defp encode_segment(map), do: map |> Jason.encode!() |> Base.url_encode64(padding: false)

  defp decode_segment(segment) do
    with {:ok, json} <- Base.url_decode64(segment, padding: false),
         {:ok, map} when is_map(map) <- Jason.decode(json) do
      {:ok, map}
    else
      _invalid -> :error
    end
  end

  # JWS carries ECDSA signatures as fixed-width R || S (RFC 7518 §3.4), while
  # Erlang produces and consumes DER. The conversion lives here so nothing else
  # needs to know either format exists.
  defp der_to_raw(der) do
    {:"ECDSA-Sig-Value", r, s} = :public_key.der_decode(:"ECDSA-Sig-Value", der)
    pad(r) <> pad(s)
  end

  defp raw_to_der(<<r::binary-size(@coordinate_bytes), s::binary-size(@coordinate_bytes)>>) do
    :public_key.der_encode(
      :"ECDSA-Sig-Value",
      {:"ECDSA-Sig-Value", :binary.decode_unsigned(r), :binary.decode_unsigned(s)}
    )
  end

  defp raw_to_der(_malformed), do: <<>>

  defp pad(integer) do
    bytes = :binary.encode_unsigned(integer)
    :binary.copy(<<0>>, @coordinate_bytes - byte_size(bytes)) <> bytes
  end
end
