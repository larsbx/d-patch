import Std

/- Abstract single-owner response admission. Server versions are causal indices,
   not status strings or device sequence numbers. No HTTP/Room refinement claim. -/
namespace OutboxPass
structure State where
  owner : Option Nat := none
  server : Nat := 0
  answer : Nat := 0
  cached : Nat := 0
  observed : Nat := 0
  deriving DecidableEq, Repr

inductive Action where
  | acquire (caller : Nat)
  | finish (caller : Nat)
  | cancel (caller : Nat)
  | advanceServer (version : Nat)

def step (s : State) : Action → State
  | .acquire a => if s.owner = none then { s with owner := some a, answer := s.server } else s
  | .finish a => if s.owner = some a then { s with owner := none, cached := s.answer, observed := max s.observed s.answer } else s
  | .cancel a => if s.owner = some a then { s with owner := none } else s
  | .advanceServer v => { s with server := max s.server v }

def Good (s : State) : Prop :=
  s.cached = s.observed ∧ s.cached ≤ s.server ∧
  (s.owner ≠ none → s.cached ≤ s.answer ∧ s.answer ≤ s.server)

theorem waiting_cancel_preserves_owner (s : State) (a : Nat) (notOwner : s.owner ≠ some a) :
    (step s (.cancel a)).owner = s.owner := by simp [step, notOwner]

theorem active_owner_excludes_acquisition (s : State) (a : Nat) (held : s.owner ≠ none) :
    step s (.acquire a) = s := by simp [step, held]

theorem step_preserves (s : State) (a : Action) (h : Good s) : Good (step s a) := by
  rcases h with ⟨same, bound, active⟩
  cases a with
  | acquire a =>
      by_cases free : s.owner = none
      · simp [step, free, Good, same]
        omega
      · simpa [step, free, Good] using And.intro same (And.intro bound active)
  | cancel a =>
      by_cases owns : s.owner = some a
      · simp [step, owns, Good, same]
        omega
      · simpa [step, owns, Good] using And.intro same (And.intro bound active)
  | finish a =>
      by_cases owns : s.owner = some a
      · have held : s.owner ≠ none := by simp [owns]
        have limits := active held
        have order : s.observed ≤ s.answer := by omega
        simp [step, owns, Good, Nat.max_eq_right order, limits.2]
      · simpa [step, owns, Good] using And.intro same (And.intro bound active)
  | advanceServer v =>
      simp only [step, Good]
      constructor
      · exact same
      constructor
      · exact Nat.le_trans bound (Nat.le_max_left _ _)
      · intro held
        exact ⟨(active held).1, Nat.le_trans (active held).2 (Nat.le_max_left _ _)⟩

def execute (s : State) (actions : List Action) : State := actions.foldl step s

theorem all_finite_histories (actions : List Action) : Good (execute {} actions) := by
  have preserved : ∀ xs s, Good s → Good (execute s xs) := by
    intro xs
    induction xs with
    | nil => intro s h; exact h
    | cons a rest ih => intro s h; exact ih (step s a) (step_preserves s a h)
  exact preserved actions {} (by simp [Good])

#print axioms all_finite_histories
#print axioms waiting_cancel_preserves_owner
end OutboxPass
