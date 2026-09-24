defmodule Dispatch.Access.CapabilitySignerTest do
  @moduledoc """
  ADR-0009's signature format, checked the way the client checks it.

  A signature test that only round-trips through the signer proves the signer
  agrees with itself. These also pin the wire shape — fixed-width R || S, the
  header the client requires — because the client is a different
  implementation and that shape is the only thing the two share.
  """

  use ExUnit.Case, async: true

  alias Dispatch.Access.CapabilitySigner

  @development Path.expand("../../../../infra/capability-signing/development.pem", __DIR__)

  setup_all do
    {:ok, key} = @development |> File.read!() |> CapabilitySigner.decode()
    %{key: key, public: CapabilitySigner.public(key)}
  end

  test "a signed document verifies and yields its claims", %{key: key, public: public} do
    token = CapabilitySigner.sign(%{"features" => ["status"]}, key)

    assert {:ok, %{"features" => ["status"]}} = CapabilitySigner.verify(token, public)
  end

  test "the header names ES256, the key, and the document type", %{key: key} do
    [header | _rest] = key |> then(&CapabilitySigner.sign(%{}, &1)) |> String.split(".")

    assert %{"alg" => "ES256", "kid" => kid, "typ" => "capability+jws"} =
             header |> Base.url_decode64!(padding: false) |> Jason.decode!()

    assert kid == key.kid
  end

  test "the signature is RFC 7518's fixed-width R || S, not DER", %{key: key} do
    # DER signatures vary between 70 and 72 bytes; a client expecting 64 would
    # reject a fraction of documents at random, which is the worst way to fail.
    for _ <- 1..20 do
      [_h, _p, signature] = key |> then(&CapabilitySigner.sign(%{}, &1)) |> String.split(".")
      assert byte_size(Base.url_decode64!(signature, padding: false)) == 64
    end
  end

  test "any altered segment fails verification", %{key: key, public: public} do
    [header, payload, signature] =
      %{"features" => ["status"]} |> CapabilitySigner.sign(key) |> String.split(".")

    widened =
      %{"features" => ["status", "approvals"]}
      |> Jason.encode!()
      |> Base.url_encode64(padding: false)

    for token <- [
          Enum.join([header, widened, signature], "."),
          Enum.join([header, payload, String.reverse(signature)], "."),
          Enum.join([header, payload], "."),
          "not a token"
        ] do
      assert {:error, :invalid_signature} = CapabilitySigner.verify(token, public)
    end
  end

  test "a document signed by another key does not verify", %{public: public} do
    {:ok, other} = generated_key()

    token = CapabilitySigner.sign(%{"features" => ["status"]}, other)

    assert {:error, :invalid_signature} = CapabilitySigner.verify(token, public)
  end

  test "the key identifier is the SPKI hash the client can compute", %{key: key} do
    # openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64url
    assert key.kid == "tOkF49ervDH_m_gl"
    assert key.kid == Dispatch.Config.development_capability_kid()
  end

  test "a key that is not P-256 is refused" do
    rsa = :public_key.generate_key({:rsa, 2048, 65_537})

    for pem <- [
          :public_key.pem_encode([:public_key.pem_entry_encode(:RSAPrivateKey, rsa)]),
          "-----BEGIN PRIVATE KEY-----\nbm90IGEga2V5\n-----END PRIVATE KEY-----\n",
          ""
        ] do
      assert {:error, :unreadable} = CapabilitySigner.decode(pem)
    end
  end

  defp generated_key do
    ec = :public_key.generate_key({:namedCurve, :secp256r1})

    [:public_key.pem_entry_encode(:PrivateKeyInfo, ec)]
    |> :public_key.pem_encode()
    |> CapabilitySigner.decode()
  end
end
