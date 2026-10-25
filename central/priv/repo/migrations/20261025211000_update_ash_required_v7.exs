defmodule Dispatch.Repo.Migrations.UpdateAshRequiredV7 do
  @moduledoc """
  Installs AshPostgres 2.15.1's version-7 ash_required definition.

  The exception is raised directly under the empty search_path, as in the
  upstream generator. No operational tables or rows change.
  """

  use Ecto.Migration

  def up do
    execute("""
    CREATE OR REPLACE FUNCTION ash_required(value ANYCOMPATIBLE, payload jsonb)
    RETURNS ANYCOMPATIBLE AS $$
    BEGIN
      IF value IS NULL THEN
        RAISE EXCEPTION 'ash_error: %', payload::text;
      END IF;

      RETURN value;
    END;
    $$ LANGUAGE plpgsql
    STABLE
    SET search_path = '';
    """)
  end

  def down do
    # Upstream keeps the corrected in-place function on rollback to version 6.
    :ok
  end
end
