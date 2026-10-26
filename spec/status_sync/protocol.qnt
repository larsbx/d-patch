// Two callers, immutable event identities, a response generated while a pass owns
// its snapshot, and an idempotent server-effect set. No HTTP/Room implementation proof.
module protocol {
  type State = { pending: Set[int], effects: Set[int], declared: bool,
    owner: int, phase: int -> int, snapshots: int -> Set[int], answers: int -> int,
    server: int, cache: int, seen: int }
  pure val ACTORS = Set(1, 2)
  var s: State
  action init = s' = { pending: Set(1), effects: Set(), declared: false,
    owner: 0, phase: Map(1 -> 0, 2 -> 0), snapshots: Map(1 -> Set(), 2 -> Set()),
    answers: Map(1 -> 0, 2 -> 0), server: 0, cache: 0, seen: 0 }
  action declare = all { not(s.declared), s' = { ...s, pending: s.pending.union(Set(2)), declared: true } }
  action request(a: int): bool = all { s.phase.get(a) == 0, s' = { ...s, phase: s.phase.set(a, 1) } }
  action acquire(serialized: bool, a: int): bool = all {
    s.phase.get(a) == 1, not(serialized) or s.owner == 0,
    val version = if (s.pending.contains(2)) 2 else if (s.pending.contains(1)) 1 else s.server
    val current = if (version > s.server) version else s.server
    s' = { ...s, owner: a, phase: s.phase.set(a, 2), snapshots: s.snapshots.set(a, s.pending),
      answers: s.answers.set(a, current), server: current, effects: s.effects.union(s.pending) }
  }
  action finish(a: int): bool = all {
    s.phase.get(a) == 2,
    val answer = s.answers.get(a)
    val hasAnswer = s.snapshots.get(a) != Set()
    s' = { ...s, owner: if (s.owner == a) 0 else s.owner, phase: s.phase.set(a, 3),
      pending: s.pending.exclude(s.snapshots.get(a)),
      cache: if (hasAnswer) answer else s.cache,
      seen: if (hasAnswer and answer > s.seen) answer else s.seen }
  }
  action cancel(a: int): bool = all {
    s.phase.get(a) == 1 or s.phase.get(a) == 2,
    s' = { ...s, owner: if (s.owner == a) 0 else s.owner, phase: s.phase.set(a, 3) }
  }
  action move(serialized: bool): bool = {
    nondet a = ACTORS.oneOf()
    any { request(a), acquire(serialized, a), finish(a), cancel(a) }
  }
  action stutter = s' = s
  val safety = all { s.cache == s.seen, s.pending.subseteq(Set(1, 2)), s.effects.subseteq(Set(1, 2)) }
}
