package models

import com.github.nscala_time.time.Imports._
import play.api.libs.json._

case class Search(text:String, timestamp:DateTime = DateTime.now)

object Search{
  implicit val f = Json.format[Search]
}