package example

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Flow
import akka.http.scaladsl.Http.ServerBinding
import akka.stream.ActorMaterializer
import scala.concurrent.Future
import scala.io.StdIn
import akka.http.scaladsl.model.ContentType.Binary
import akka.http.scaladsl.model.HttpEntity.Chunked
import scala.concurrent.Await
import scala.util.{ Failure, Success }
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.HttpEntity.Strict
import scala.concurrent.duration._

object Hello extends App {
  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  val requestHandler: HttpRequest => Future[HttpResponse] = {  
    case HttpRequest(POST, Uri.Path("/process"), _, entity, _) => {
      entity match {
        case Chunked(contentType, chunks) => {
          val res = chunks
            .takeWhile(!_.isLastChunk)
            .filter(_.data().decodeString("UTF-8") != " ")
            .mapAsync(4)(ch => {
              val word = ch.data().decodeString("UTF-8");
              val uri = Uri("http://127.0.0.1").withPort(8000).withPath(Path(s"/score/${word}"))
              val req = HttpRequest(uri=uri)
              val fs = Source.fromFuture(Http().singleRequest(req));
              fs.runWith(Sink.head).map(_.entity).flatMap({
                case Strict(contentType, data) => {
                  val number = data.decodeString("UTF-8")
                  // println(s"$word: ${number}")
                  Future.successful(number)
                }
                case _ => Future.failed(new Exception("something goes wrong"))
              })
            })
            .runWith(Sink.fold[Int, String](0) { (a, b) => a + b.toInt})
            .map(v => HttpResponse(entity = v.toString()))
          
          val t0 = System.currentTimeMillis()
          res.foreach(_ => {
            val t1 = System.currentTimeMillis()
            println(s"Elapsed time: ${(t1 - t0) / 1000} s")
          })
          res
        }
        case _ => Future { HttpResponse(500) }
      }
    }
    case _ => Future { HttpResponse(500) }
  }
  
  val bindingFuture = Http().bindAndHandleAsync(requestHandler, "localhost", 8888);

  bindingFuture.failed.foreach { ex =>
    println(ex)
  }

  StdIn.readLine()
  bindingFuture
    .flatMap(_.unbind())
    .onComplete(_ => system.terminate())
}
