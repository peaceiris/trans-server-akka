package io.github.nwtgck.trans_server

import java.io.File

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpsConnectionContext}
import akka.stream.ActorMaterializer
import slick.driver.H2Driver.api._

import scala.concurrent.Future
import scala.util.Try

/**
  * Created by Ryo on 2017/04/23.
  */
object Main {


  def main(args: Array[String]): Unit = {

    // Import Settings
    import Setting._

    // Decide ports
    val (httpPort: Int, httpsPort: Int) = args match {
      case Array(httpPortStr, httpsPortStr) =>
        (
          Try(httpPortStr.toInt).getOrElse(DEFAULT_HTTP_PORT),
          Try(httpsPortStr.toInt).getOrElse(DEFAULT_HTTPS_PORT)
        )
      case _ =>
        (sys.env.getOrElse("PORT", DEFAULT_HTTP_PORT.toString).toInt, DEFAULT_HTTPS_PORT)
    }

//     Create a memory-base db
//    val db = Database.forConfig("h2mem-trans")

    // Create a file-base db
    val db = Database.forConfig("h2file-trans")

    implicit val system = ActorSystem("trans-server-actor-system")
    implicit val materializer = ActorMaterializer()
    import concurrent.ExecutionContext.Implicits.global

    // Create core system of trans server
    val core: Core = new Core(db, fileDbPath = Setting.File_DB_PATH)


    // Create File DB if non
    {
      val fileDbDir = new File(File_DB_PATH)
      if (!fileDbDir.exists()) {
        fileDbDir.mkdirs()
      }
    }

    // Scheduling for cleaning up dead files
    // (from: https://doc.akka.io/docs/akka/2.5/scheduler.html?language=scala)
    system.scheduler.schedule(CleanupDuration, CleanupDuration, new Runnable {
      override def run(): Unit = {
        // Clean up dead files
        core.removeDeadFiles()
      }
    })

    for {
      // Create a table if not exist
      _ <- Tables.createTablesIfNotExist(db)

      // Run the HTTP server
      _ <- Http().bindAndHandle(core.route, HOST, httpPort)
      _ <- {
        if(new File(Setting.KEY_STORE_PATH).exists()) {
          // Generate a HttpsConnectionContext
          val httpsConnectionContext: HttpsConnectionContext = Util.generateHttpsConnectionContext(
            Setting.KEY_STORE_PASSWORD,
            Setting.KEY_STORE_PATH
          )
          // Run the HTTPS server
          Http().bindAndHandle(core.route, HOST, httpsPort, connectionContext = httpsConnectionContext)
        } else {
          Future.successful()
        }
      }
      _ <- Future.successful{println(s"Listening HTTP  on ${httpPort}...")}
      _ <- Future.successful{println(s"Listening HTTPS on ${httpsPort}...")}
    } yield ()
  }

}
