import java.io.File
import java.nio.file.StandardOpenOption

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by Ryo on 2017/04/23.
  */
object Main {


  def main(args: Array[String]): Unit = {

    // Import Settings
    import Setting._

    // Decide port
    val port: Int = args match {
      case Array(portStr) =>
        Try(portStr.toInt).getOrElse(DEFAULT_PORT)
      case _ =>
        DEFAULT_PORT
    }

    implicit val system = ActorSystem("trans-server-actor-system")
    implicit val materializer = ActorMaterializer()
    import concurrent.ExecutionContext.Implicits.global


    // Create File DB if non
    {
      val fileDbDir = new File(File_DB_PATH)
      if (!fileDbDir.exists()) {
        fileDbDir.mkdirs()
      }
    }

    for {
      // Run the HTTP server
      _ <- Http().bindAndHandle(route, HOST, port)
      _ <- Future.successful{print(s"Listenning on ${port}...")}
    } yield ()
  }


  /**
    * Http Server's Routing
    */
  def route(implicit materializer: ActorMaterializer): Route = {
    // for routing DSL
    import akka.http.scaladsl.server.Directives._
    // for using XML
    import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport._
    // for using settings
    import Setting._
    // for Futures
    import concurrent.ExecutionContext.Implicits.global


    // "Get /" for confirming whether the server is running
    (get & pathSingleSlash) {
      complete(<h1>trans server is runnning</h1>)
    } ~
    // "Post /" for client-sending a file
    (post & pathSingleSlash) {

      var fileId: String = null
      var storeFilePath: String = null

      // Generate File ID and storeFilePath
      do {
        fileId = generateFileId()
        storeFilePath = List(File_DB_PATH, fileId).mkString(File.separator)
      } while (new File(storeFilePath).exists())


      // Get a file from client and store it
      // hint from: http://doc.akka.io/docs/akka-http/current/scala/http/implications-of-streaming-http-entity.html#implications-of-streaming-http-entities
      withoutSizeLimit {
        extractDataBytes { bytes =>
          val finshiedWriting = bytes.runWith(FileIO.toPath(new File(storeFilePath).toPath, options = Set(StandardOpenOption.WRITE, StandardOpenOption.CREATE)))
          onComplete(finshiedWriting) { ioResult =>
            println(ioResult)
            complete(s"${fileId}\n")
          }
        }
      }
    } ~
    // "Get /xyz" for client-getting the specified file
    (get & path(Remaining)) { fileId =>
      val gettingFilePath = List(File_DB_PATH, fileId).mkString(File.separator)

      val file = new File(gettingFilePath)

      // File exists
      if (file.exists()) {
        complete(
          HttpEntity.fromPath(ContentTypes.NoContentType, file.toPath)
        )
      } else {
        complete(StatusCodes.NotFound, s"File ID '${fileId}' not found\n")
      }

    }
  }

  /**
    * Generate random File ID
    * @return Random File ID
    */
  def generateFileId(): String = {
    // 1 ~ 9 + 'a' ~ 'z'
    val candidates: Seq[String] = ((0 to 9) ++ ('a' to 'z')).map(_.toString)
    val r = scala.util.Random
    val i = (1 to 3).map{_ =>
      val idx = r.nextInt(candidates.length)
      candidates(idx)
    }
    i.mkString
  }
}