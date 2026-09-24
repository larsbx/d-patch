defmodule DispatchWeb.Schemas.RoleAssignmentChoice do
  @moduledoc """
  One role assignment a principal may select (Section 24.6).

  Identifier and label only. Section 4.3's switcher needs to name the choices;
  what each one permits is the capability document's business, and only for
  the assignment actually selected.
  """

  alias OpenApiSpex.Schema

  require OpenApiSpex

  OpenApiSpex.schema(%{
    title: "RoleAssignmentChoice",
    type: :object,
    properties: %{
      id: %Schema{type: :string, format: :uuid},
      label: %Schema{type: :string}
    },
    required: [:id, :label],
    additionalProperties: false
  })
end

defmodule DispatchWeb.Schemas.RoleAssignmentList do
  @moduledoc "The body of `GET /v1/me/role-assignments`."

  alias DispatchWeb.Schemas.RoleAssignmentChoice
  alias OpenApiSpex.Schema

  require OpenApiSpex

  OpenApiSpex.schema(%{
    title: "RoleAssignmentList",
    type: :object,
    properties: %{
      role_assignments: %Schema{type: :array, items: RoleAssignmentChoice}
    },
    required: [:role_assignments],
    additionalProperties: false
  })
end

defmodule DispatchWeb.Schemas.CapabilityDocumentResponse do
  @moduledoc """
  The body of `GET /v1/role-capabilities` (Section 23.2, ADR-0009).

  The document itself is the `document` string: a compact JWS whose payload the
  client uses only after verifying the signature against its pinned key.
  Nothing outside the signature is trusted, so nothing else is repeated here.
  """

  alias OpenApiSpex.Schema

  require OpenApiSpex

  OpenApiSpex.schema(%{
    title: "CapabilityDocumentResponse",
    type: :object,
    properties: %{
      document: %Schema{
        type: :string,
        description:
          "Compact JWS (ES256, typ capability+jws). Payload: schema_version, sub, " <>
            "tenant_id, participant_id, role_assignment_id, role, capabilities, " <>
            "features, status_options, iat, exp."
      }
    },
    required: [:document],
    additionalProperties: false
  })
end
