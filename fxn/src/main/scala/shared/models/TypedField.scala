package shared.models


/** This trait represents a UI form field type, recording field name in `value` and field value type in `T`. */
trait TypedField[T] {
  val value: String
}

object TypedField {

  def apply[T](keyName: String): TypedField[T] = new TypedField[T] {
    override val value: String = keyName
  }
}