package controllers

import scala.concurrent._
import ExecutionContext.Implicits.global

import play.api.libs.iteratee._

import play.api._
import play.api.mvc._
import play.api.libs._
import play.api.libs.ws._

import play.api.libs.json._
import play.api.libs.functional._
import play.api.libs.functional.syntax._
import play.api.libs.json.Reads._


object Application extends Controller {
  
  def index = Action { implicit req =>
    Ok(views.html.index())
  }

  implicit val writeTweetAsJson = Json.writes[Tweet]

  def search(query: String) = Action {

    val asJson: Enumeratee[Tweet,JsValue] = Enumeratee.map { 
      case t => Json.toJson(t)
    }

    // Serve a 200 OK text/event-stream
    Ok.feed(
      Tweet.search(query) &> asJson &> EventSource()
    ).as("text/event-stream")
  }

  case class Tweet(id: String, text: String, image: String)

  object Tweet {

    // Reads the twitter search API response, as a Seq[Tweet]
    implicit val readTweet: Reads[Seq[Tweet]] = 

      // Start with 'results' property
      (__ \ "results").read(
        // It contains an array, so for each item
        seq(
          // Read the tweet id encoded as String
          (__ \ "id_str").read[String] and

          // Read the tweet text content
          (__ \ "text").read[String] and

          // If there is an {entities: {media: [] ... property
          (__ \ "entities" \ "media").readNullable(
            // It contains an array, so for each item
            seq(
              // Read the image URL
              (__ \ "media_url").read[String]
            )

          // Let's transform the Option[Seq[String]] to an simple Option[String]
          // since we care only about the first image URL if there is any.
          ).map(_.flatMap(_.headOption))

          // Transform all this to a (String, String, Option[String]) tuple
          tupled
        )
        .map(
          // Keep only the tuple containing an Image (third part of the tuple is Some)
          // and transform them to Tweet instances.
          _.collect {
            case (id, text, Some(image)) => Tweet(id, text, image)
          }
        )
      )

    def fetchPage(query: String, page: Int): Future[Seq[Tweet]] = {

      // Fetch the twitter search API with the corresponding parameters (see the Twitter API documentation)
      WS.url("http://search.twitter.com/search.json").withQueryString(
        "q" -> query,
        "page" -> page.toString,
        "include_entities" -> "true",
        "rpp" -> "100" 
      ).get().map(r => r.status match {

        // We got a 200 OK response, try to convert the JSON body to a Seq[Tweet]
        // by using the previously defined implicit Reads[Seq[Tweet]]
        case 200 => r.json.asOpt[Seq[Tweet]].getOrElse(Nil)

        // Really? There is nothing todo for us
        case x => sys.error(s"Bad response status $x")
      })

    }

    /**
     * Create a stream of Tweet object from a Twitter query (such as #devoxx)
     */
    def search(query: String): Enumerator[Tweet] = {

      // Flatenize an Enumerator[Seq[Tweet]] as n Enumerator[Tweet]
      val flatenize = Enumeratee.mapConcat[Seq[Tweet]](identity) 

      // Schedule
      val schedule = Enumeratee.mapM[Tweet](t => play.api.libs.concurrent.Promise.timeout(t, 1000))

      // Create a stream of tweets from multiple twitter API calls
      val tweets = Enumerator.unfoldM(1) {
        case page => fetchPage(query, page).map { tweets => 
          Option(tweets).filterNot(_.isEmpty).map( tweets => (page + 1, tweets) )
        }
      } 

      // Compose the final stream
      tweets &> flatenize &> schedule
    }

  }
  
}