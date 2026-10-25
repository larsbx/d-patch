import Std

/- Abstract ticket indices name fresh in-process intent identities. Every
   value/history is finite. This is not verification of Kotlin, storage, HTTP
   delivery, token uniqueness or cryptography. Lists are observed by membership,
   corresponding to Quint's sets. No bound is imposed on the history here. -/
namespace AuthIntent

structure State where
  epoch : Nat := 0
  localToken : Option Nat := none
  admittedAt : Option Nat := none
  logoutBarrier : Option Nat := none
  tickets : List Nat := []
  issued : List Nat := []
  completed : List Nat := []
  requested : List Nat := []
  revoked : List Nat := []
  confirmed : List Nat := []
  discarded : List Nat := []
  deriving DecidableEq, Repr

inductive Action where
  | begin | issue (ticket : Nat) | signOut | finish (ticket : Nat)
  | deliver (ticket : Nat) | confirm (ticket : Nat)
  deriving DecidableEq, Repr

def step (s : State) : Action → State
  | .begin => { s with epoch := s.epoch + 1, tickets := (s.epoch + 1) :: s.tickets }
  | .issue t => if t ∈ s.tickets ∧ t ∉ s.issued then
      { s with issued := t :: s.issued } else s
  | .signOut => { s with epoch := s.epoch + 1, localToken := none, admittedAt := none, logoutBarrier := some (s.epoch + 1), requested := match s.localToken with | none => s.requested | some t => t :: s.requested }
  | .finish t => if t ∈ s.issued ∧ t ∉ s.completed then
      if t = s.epoch then
        { s with localToken := some t, admittedAt := some s.epoch, completed := t :: s.completed }
      else
        { s with completed := t :: s.completed, discarded := t :: s.discarded, requested := t :: s.requested }
      else s
  | .deliver t => if t ∈ s.requested then { s with revoked := t :: s.revoked } else s
  | .confirm t => if t ∈ s.revoked then { s with confirmed := t :: s.confirmed } else s

def execute (s : State) (actions : List Action) : State := actions.foldl step s

def LocalSound (s : State) : Prop :=
  ∀ t, s.localToken = some t → s.admittedAt = some t

def ConfirmedSound (s : State) : Prop :=
  ∀ t, t ∈ s.confirmed → t ∈ s.revoked

theorem signOut_removes_local (s : State) : (step s .signOut).localToken = none := rfl

theorem stale_completion_preserves_local (s : State) (t : Nat) (stale : t ≠ s.epoch) :
    (step s (.finish t)).localToken = s.localToken := by
  by_cases active : t ∈ s.issued ∧ t ∉ s.completed <;> simp [step, stale, active]

theorem pre_signOut_ticket_cannot_reinstall (s : State) (t : Nat) (captured : t ≤ s.epoch) :
    (step (step s .signOut) (.finish t)).localToken = none := by
  have stale : t ≠ s.epoch + 1 := by omega
  exact (stale_completion_preserves_local (step s .signOut) t stale)

theorem pre_begin_ticket_cannot_replace_local (s : State) (t : Nat) (captured : t ≤ s.epoch) :
    (step (step s .begin) (.finish t)).localToken = s.localToken := by
  have stale : t ≠ s.epoch + 1 := by omega
  exact (stale_completion_preserves_local (step s .begin) t stale)

theorem step_local_sound (s : State) (a : Action) (h : LocalSound s) :
    LocalSound (step s a) := by
  cases a with
  | begin => exact h
  | signOut => simp [step, LocalSound]
  | issue t => by_cases active : t ∈ s.tickets ∧ t ∉ s.issued <;> simpa [step, active, LocalSound] using h
  | finish t =>
      by_cases active : t ∈ s.issued ∧ t ∉ s.completed
      · by_cases current : t = s.epoch
        · subst t; simp [step, active, LocalSound]
        · simpa [step, active, current, LocalSound] using h
      · simpa [step, active] using h
  | deliver t => by_cases sent : t ∈ s.requested <;> simpa [step, sent, LocalSound] using h
  | confirm t => by_cases done : t ∈ s.revoked <;> simpa [step, done, LocalSound] using h

theorem step_confirmed_sound (s : State) (a : Action) (h : ConfirmedSound s) :
    ConfirmedSound (step s a) := by
  cases a with
  | begin => exact h
  | signOut => exact h
  | issue t => by_cases active : t ∈ s.tickets ∧ t ∉ s.issued <;> simpa [step, active, ConfirmedSound] using h
  | finish t =>
      by_cases active : t ∈ s.issued ∧ t ∉ s.completed
      · by_cases current : t = s.epoch
        · subst t; simpa [step, active, ConfirmedSound] using h
        · simpa [step, active, current, ConfirmedSound] using h
      · simpa [step, active] using h
  | deliver t =>
      by_cases sent : t ∈ s.requested
      · simp only [step, ite_eq_left sent, ConfirmedSound]
        intro u member; exact List.mem_cons_of_mem t (h u member)
      · simpa [step, sent] using h
  | confirm t =>
      by_cases done : t ∈ s.revoked
      · simp only [step, ite_eq_left done, ConfirmedSound, List.mem_cons]
        intro u member
        rcases member with equal | old
        · simpa [equal] using done
        · exact h u old
      · simpa [step, done] using h

theorem step_epoch_monotone (s : State) (a : Action) : s.epoch ≤ (step s a).epoch := by
  cases a with
  | begin => simp [step]
  | signOut => simp [step]
  | issue t => by_cases active : t ∈ s.tickets ∧ t ∉ s.issued <;> simp [step, active]
  | finish t =>
      by_cases active : t ∈ s.issued ∧ t ∉ s.completed
      · by_cases current : t = s.epoch
        · subst t; simp [step, active]
        · simp [step, active, current]
      · simp [step, active]
  | deliver t => by_cases sent : t ∈ s.requested <;> simp [step, sent]
  | confirm t => by_cases done : t ∈ s.revoked <;> simp [step, done]

theorem execute_epoch_monotone (s : State) (actions : List Action) :
    s.epoch ≤ (execute s actions).epoch := by
  induction actions generalizing s with
  | nil => exact Nat.le_refl _
  | cons a rest ih => exact Nat.le_trans (step_epoch_monotone s a) (ih (step s a))

/-- Even if arbitrary new sign-ins finish meanwhile, an old completion cannot replace them. -/
theorem pre_signOut_ticket_cannot_replace_after_interleavings
    (s : State) (t : Nat) (actions : List Action) (captured : t ≤ s.epoch) :
    let later := execute (step s .signOut) actions
    (step later (.finish t)).localToken = later.localToken := by
  have monotone := execute_epoch_monotone (step s .signOut) actions
  have stale : t ≠ (execute (step s .signOut) actions).epoch := by
    change s.epoch + 1 ≤ (execute (step s .signOut) actions).epoch at monotone
    omega
  exact stale_completion_preserves_local _ t stale

theorem all_finite_histories_local_sound (actions : List Action) :
    LocalSound (execute {} actions) := by
  have preserved : ∀ (xs : List Action) (s : State), LocalSound s → LocalSound (execute s xs) := by
    intro xs
    induction xs with
    | nil => intro s h; exact h
    | cons a rest ih =>
        intro s h
        exact ih (step s a) (step_local_sound s a h)
  exact preserved actions {} (by simp [LocalSound])

theorem all_finite_histories_confirmed_sound (actions : List Action) :
    ConfirmedSound (execute {} actions) := by
  have preserved : ∀ (xs : List Action) (s : State), ConfirmedSound s → ConfirmedSound (execute s xs) := by
    intro xs
    induction xs with
    | nil => intro s h; exact h
    | cons a rest ih =>
        intro s h
        exact ih (step s a) (step_confirmed_sound s a h)
  exact preserved actions {} (by simp [ConfirmedSound])

/- Negative theorem: clearing localToken credentials does not itself end an issued
   remote token. It requires a separate delivery event; acknowledgement may
   still be absent after successful delivery. -/
theorem local_removal_does_not_imply_remote_revocation :
    let s := execute {} [.begin, .issue 1, .finish 1, .signOut]
    s.localToken = none ∧ 1 ∈ s.issued ∧ 1 ∉ s.revoked := by decide

theorem revocation_does_not_imply_acknowledgement :
    let s := execute {} [.begin, .issue 1, .finish 1, .signOut, .deliver 1]
    1 ∈ s.revoked ∧ 1 ∉ s.confirmed := by decide

#print axioms all_finite_histories_local_sound
#print axioms all_finite_histories_confirmed_sound
#print axioms pre_signOut_ticket_cannot_reinstall
#print axioms pre_signOut_ticket_cannot_replace_after_interleavings

end AuthIntent
