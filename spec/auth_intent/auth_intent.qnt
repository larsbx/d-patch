module auth_intent {
  import protocol.* from "./protocol"
  action step = any { begin, issue, signOut, finish(true), deliver, confirm, stutter }
  val noDiscardedGrant = s.discarded == Set()
  val noConfirmedLogout = s.confirmed == Set()
  // A local sign-out is not evidence that remote invalidation was delivered.
  val noLocalOnlyLogout = not(s.local == -1 and s.issued.exclude(s.revoked) != Set())
  run lateSignOutTest = init.then(begin).then(issueTicket(1)).then(signOut).then(finishTicket(true, 1))
    .then(all { assert(s.local == -1 and s.discarded == Set(1)), s' = s })
  run newerSignInTest = init.then(begin).then(issueTicket(1)).then(begin).then(finishTicket(true, 1))
    .then(all { assert(s.local == -1 and s.discarded == Set(1)), s' = s })
  run newerSessionWinsTest = init.then(begin).then(issueTicket(1)).then(begin).then(issueTicket(2))
    .then(finishTicket(true, 2)).then(finishTicket(true, 1))
    .then(all { assert(s.local == 2 and s.discarded == Set(1)), s' = s })
}
