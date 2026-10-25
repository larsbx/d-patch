// Negative control: same state/actions, with only the admission fence removed.
module auth_intent_unfenced {
  import protocol.* from "../protocol"
  action step = any { begin, issue, signOut, finish(false), deliver, confirm, stutter }
  run resurrectionTest = init.then(begin).then(issueTicket(1)).then(signOut).then(finishTicket(false, 1))
    .then(all { assert(s.local == 1 and not(safety)), s' = s })
}
