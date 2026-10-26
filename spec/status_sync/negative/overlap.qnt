module overlap {
  import protocol.* from "../protocol"
  action step = any { declare, move(false), stutter }
  run staleResponseTest = init.then(request(1)).then(acquire(false, 1)).then(declare)
    .then(request(2)).then(acquire(false, 2)).then(finish(2)).then(finish(1))
    .then(all { assert(s.cache == 1 and s.seen == 2 and not(safety)), s' = s })
}
