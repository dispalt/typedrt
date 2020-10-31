package com.goodcover.typedrt.serialization

// Marker trait for typed runtime wire serialization
// Make it more specific so it get's chosen for serialization
trait MarkerMessage extends Serializable
