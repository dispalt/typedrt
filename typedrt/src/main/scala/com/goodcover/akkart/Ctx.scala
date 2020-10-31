package com.goodcover.akkart

import com.goodcover.encoding.KeyCodec

case class Ctx(lastSequenceNr: Long, eventCount: Long, name: String)

case class KeyedCtx[C](key: C, ctx: Ctx)
