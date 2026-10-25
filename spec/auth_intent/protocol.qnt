// Abstraction of AuthSession, not a proof of Kotlin or of network delivery.
// One begin+redirect-consumption is abstracted as `begin`: each issued grant
// has its own ticket. No cryptographic identity or token contents are modelled.
module protocol {
  type State = {
    epoch: int, local: int, installedAt: int, lastOut: int,
    tickets: Set[int], issued: Set[int], completed: Set[int],
    requested: Set[int], revoked: Set[int], confirmed: Set[int], discarded: Set[int],
  }
  pure val EPOCHS = Set(1, 2, 3)
  var s: State
  action init = s' = {
    epoch: 0, local: -1, installedAt: -1, lastOut: -1,
    tickets: Set(), issued: Set(), completed: Set(),
    requested: Set(), revoked: Set(), confirmed: Set(), discarded: Set(),
  }
  action begin = all {
    s.epoch < 3,
    s' = { ...s, epoch: s.epoch + 1, tickets: s.tickets.union(Set(s.epoch + 1)) },
  }
  action issueTicket(t: int): bool = all {
    s.tickets.contains(t), not(s.issued.contains(t)),
    s' = { ...s, issued: s.issued.union(Set(t)) },
  }
  action issue = { nondet t = EPOCHS.oneOf(); issueTicket(t) }
  action signOut = all {
    s.epoch < 3,
    s' = { ...s, epoch: s.epoch + 1, local: -1, installedAt: -1,
      lastOut: s.epoch + 1,
      requested: if (s.local >= 0) s.requested.union(Set(s.local)) else s.requested },
  }
  action finishTicket(fenced: bool, t: int): bool = all {
    s.issued.contains(t), not(s.completed.contains(t)),
    s' = if (not(fenced) or t == s.epoch)
      { ...s, local: t, installedAt: s.epoch, completed: s.completed.union(Set(t)) }
    else
      { ...s, completed: s.completed.union(Set(t)),
        discarded: s.discarded.union(Set(t)), requested: s.requested.union(Set(t)) }
  }
  action finish(fenced: bool): bool = { nondet t = EPOCHS.oneOf(); finishTicket(fenced, t) }
  // Delivery can revoke a session without the acknowledgement arriving.
  action deliver = {
    nondet t = EPOCHS.oneOf()
    all { s.requested.contains(t), not(s.revoked.contains(t)), s' = { ...s, revoked: s.revoked.union(Set(t)) } }
  }
  action confirm = {
    nondet t = EPOCHS.oneOf()
    all { s.revoked.contains(t), not(s.confirmed.contains(t)), s' = { ...s, confirmed: s.confirmed.union(Set(t)) } }
  }
  action stutter = s' = s

  val safety = all {
    s.tickets.forall(t => t > 0 and t <= s.epoch),
    s.issued.subseteq(s.tickets), s.completed.subseteq(s.issued),
    s.requested.subseteq(s.issued), s.revoked.subseteq(s.requested),
    s.confirmed.subseteq(s.revoked),
    s.local == -1 or all {
      s.local == s.installedAt, s.local > s.lastOut,
      s.issued.contains(s.local), not(s.discarded.contains(s.local)),
    },
  }
}
