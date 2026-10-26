module status_sync {
  import protocol.* from "./protocol"
  action step = any { declare, move(true), stutter }
  val noWaitingCancellation = not(s.phase.get(1) == 2 and s.phase.get(2) == 3 and s.owner == 1)
  val noDeliveredUnsettled = s.effects.exclude(Set(1, 2).exclude(s.pending)) == Set()
  val noTwoEffects = s.effects != Set(1, 2)
  run waitingCancellationTest = init.then(request(1)).then(acquire(true, 1)).then(request(2)).then(cancel(2))
    .then(all { assert(s.owner == 1 and s.pending == Set(1)), s' = s })
  run ownerCancellationReplayTest = init.then(request(1)).then(acquire(true, 1)).then(cancel(1))
    .then(request(2)).then(acquire(true, 2)).then(finish(2))
    .then(all { assert(s.pending == Set() and s.effects == Set(1) and s.cache == 1), s' = s })
  run newDeclarationTest = init.then(request(1)).then(acquire(true, 1)).then(declare)
    .then(request(2)).then(finish(1)).then(acquire(true, 2)).then(finish(2))
    .then(all { assert(s.pending == Set() and s.effects == Set(1, 2) and s.cache == 2), s' = s })
}
