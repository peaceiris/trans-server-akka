import java.io.{File, FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
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
        (DEFAULT_HTTP_PORT, DEFAULT_HTTPS_PORT)
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

    // Generate a HttpsConnectionContext
    val httpsConnectionContext: HttpsConnectionContext = generateHttpsConnectionContext()

    for {
      // Create a table if not exist
      _ <- Tables.createTablesIfNotExist(db)

      // Run the HTTP server
      _ <- Http().bindAndHandle(core.route, HOST, httpPort)
      _ <- Http().bindAndHandle(core.route, HOST, httpsPort, connectionContext = httpsConnectionContext)
      _ <- Future.successful{println(s"Listening HTTP  on ${httpPort}...")}
      _ <- Future.successful{println(s"Listening HTTPS on ${httpsPort}...")}
    } yield ()
  }



  /**
    * Generate a HttpsConnectionContext
    *
    * hint from: http://doc.akka.io/docs/akka-http/current/scala/http/server-side-https-support.html
    * @return
    */
  def generateHttpsConnectionContext(): HttpsConnectionContext = {
    import Setting._

    // Manual HTTPS configuration
    val password: Array[Char] = KEY_STORE_PASSWORD.toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks: KeyStore = KeyStore.getInstance("jks")
    val keystore: InputStream = new FileInputStream(KEY_STORE_PATH)

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    val httpsConnectionContext: HttpsConnectionContext = ConnectionContext.https(sslContext)

    httpsConnectionContext
  }
}
