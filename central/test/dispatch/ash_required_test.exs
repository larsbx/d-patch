defmodule Dispatch.AshRequiredTest do
  use ExUnit.Case, async: false

  @moduletag :integration

  setup do
    :ok = Ecto.Adapters.SQL.Sandbox.checkout(Dispatch.Repo)
  end

  test "the upgraded required-value function preserves a present value" do
    assert %{rows: [[42]]} =
             Ecto.Adapters.SQL.query!(
               Dispatch.Repo,
               "SELECT public.ash_required(42::integer, '{}'::jsonb)"
             )
  end

  test "a missing value raises the Ash error under the function's empty search path" do
    assert_raise Postgrex.Error, ~r/ash_error: /, fn ->
      Ecto.Adapters.SQL.query!(
        Dispatch.Repo,
        "SELECT public.ash_required(NULL::integer, '{}'::jsonb)"
      )
    end
  end
end
