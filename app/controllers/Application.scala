package controllers

import javax.inject.Inject
import play.api._
import play.api.mvc._
import play.api.libs.ws._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import play.api.libs.json._
import models._
import models.Search._

import play.modules.reactivemongo.{ // ReactiveMongo Play2 plugin
  MongoController,
  ReactiveMongoApi,
  ReactiveMongoComponents
}

// BSON-JSON conversions/collection
import play.modules.reactivemongo.json._
import play.modules.reactivemongo.json.collection._

import reactivemongo.api.Cursor
import reactivemongo.api.{ReadPreference, QueryOpts}

class Application @Inject() (val reactiveMongoApi: ReactiveMongoApi, ws: WSClient) 
  extends Controller 
  with MongoController 
  with ReactiveMongoComponents {

  def collection: JSONCollection = db.collection[JSONCollection]("searches")

  def index = Action.async { implicit request =>

    val cursor: Cursor[Search] = 
      collection
      .find(JsObject(Seq.empty[(String, JsValue)]))
      .sort(Json.obj("timestamp" -> -1))
      .cursor[Search](ReadPreference.primary)

    cursor.collect[List](10) map { searches =>  
      Ok(views.html.index(searches))
    }

    
  }

  def translate = Action.async(parse.json) { implicit request =>

    request.body.validate[JsObject].fold(
      errors => {
        Future { 
          Ok(Json.obj("error" -> s"There was an error decoding json"))
        }
      }, 
      json => {
        (json \ "text").validate[String] match {
          case e:JsSuccess[String] => 

            val translateText = 
              ws.url("https://www.googleapis.com/language/translate/v2")
                .withHeaders("Accept" -> "application/json")
                .withRequestTimeout(1000)
                .withQueryString("q" -> s"${e.get}", "target" -> "es", 
                  "key" -> "AIzaSyAd49NdMyCVDikXEFiXiGFmwsar7jaXMkk")
                .get()
                .map(x => 
                  (
                    x.json \ 
                    "data" \ 
                    "translations" \\ 
                    "translatedText"
                  ).toList.map(_.as[String]).headOption
                ) recover {
                  case e:Throwable => None
                }

            translateText flatMap {text => 
              text match {
                case Some(translatedText) => 
                collection
                .insert(Search(e.get))
                .map(lastError =>
                   if(lastError.ok)
                      Ok(Json.obj("translated" -> translatedText))
                    else
                      Ok(Json.obj(
                        "translated" -> translatedText,
                        "error" -> "Text couldn't be saved on database")))

                case None => 
                  Future{
                    Ok(Json.obj(
                      "error" -> s"Remote service is not available, please try again..."))
                  }
              }
            }

          case e:JsError => 
            Future {
              Ok(Json.obj(
                "error" -> s"There was an error getting text for translation"))
            }
        }
      }
    )
  }
}
