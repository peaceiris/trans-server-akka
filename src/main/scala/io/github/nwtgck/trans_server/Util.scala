package io.github.nwtgck.trans_server

import java.io.{FileInputStream, InputStream, PrintWriter, StringWriter}
import java.security.{KeyStore, SecureRandom}
import java.util.Base64

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.extractRequest
import akka.http.scaladsl.server.{Directive, Directive1, Route}
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import scala.concurrent.Future

object Util {
  // Generate hashed key
  // (from: http://www.casleyconsulting.co.jp/blog-engineer/java/%E3%80%90java-se-8%E9%99%90%E5%AE%9A%E3%80%91%E5%AE%89%E5%85%A8%E3%81%AA%E3%83%91%E3%82%B9%E3%83%AF%E3%83%BC%E3%83%89%E3%82%92%E7%94%9F%E6%88%90%E3%81%99%E3%82%8B%E6%96%B9%E6%B3%95/)
  def generateHashedKey1(key: String, salt: String): String = {
    import javax.crypto.SecretKey
    import javax.crypto.SecretKeyFactory
    import javax.crypto.spec.PBEKeySpec
    import java.security.MessageDigest

    val messageDigest = MessageDigest.getInstance("SHA-256")
    val passCharAry: Array[Char] = key.toCharArray
    messageDigest.update(salt.getBytes)
    val hashedSalt: Array[Byte] = messageDigest.digest

    val keySpec: PBEKeySpec = new PBEKeySpec(passCharAry, hashedSalt, 10000, 256)

    val skf: SecretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")


    var secretKey: SecretKey = skf.generateSecret(keySpec)

    val passByteAry: Array[Byte] = secretKey.getEncoded

    // Convert byte array to a string
    val sb: StringBuilder = new StringBuilder(64)
    for (b <- passByteAry) {
      sb.append("%02x".format(b & 0xff))
    }
    sb.toString()
  }

  /**
    * Generate a HttpsConnectionContext
    *
    * hint from: http://doc.akka.io/docs/akka-http/current/scala/http/server-side-https-support.html
    * @return
    */
  def generateHttpsConnectionContext(keystorePassword: String, keyStorePath: String): HttpsConnectionContext = {

    // Manual HTTPS configuration
    val password: Array[Char] = keystorePassword.toCharArray // do not store passwords in code, read them from somewhere safe!

    val ks: KeyStore = KeyStore.getInstance("jks")
    val keystore: InputStream = new FileInputStream(keyStorePath)

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

  // Extract user name and password in Basic Authentication if exist
  // NOTE: Should use `def`? Why not `val`?
  // (from: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/custom-directives.html)
  def extractBasicAuthUserAndPasswordOpt: Directive1[Option[(String, String)]] = Directive[Tuple1[Option[(String, String)]]]{ f =>
    extractRequest{request =>
      request.headers.find(_.name == "Authorization") match {
        // "Authorization" header is found
        case Some(basicAuthHeader) =>
          // Regexp for Basic Authentication
          val regexp = """Basic (.*)""".r
          basicAuthHeader.value() match {
            // Match "Basic ..."
            case regexp(userAndPasswdBase64) =>
              // Decode basic64
              val decodedStr = new String(Base64.getDecoder().decode(userAndPasswdBase64))
              // Split by ":"
              decodedStr.split(":") match {
                // Format is correct
                case Array(user, password) =>
                  f(Tuple1(Some((user, password))))
                case _ =>
                  f(Tuple1(None))
              }
            // Not match "Basic ..."
            case _ =>
              f(Tuple1(None))
          }
        // "Authorization" header is not found
        case None =>
          f(Tuple1(None))
      }
    }
  }

  // Redirect http to https in Heroku or IBM Cloud (Bluemix)
  // (from: https://stackoverflow.com/a/39227668/2885946)
  def xForwardedProtoHttpsRedirectRoute(route: Route): Route = {
    import akka.http.scaladsl.server.Directives._

    extractUri{uri =>
      optionalHeaderValueByName("X-Forwarded-Proto") {
        // NOTE: `xForwardedProto != "https"` should be OK, but Glitch gives like "https,http,http"
        case Some(xForwardedProto) if !xForwardedProto.contains("https") =>
          redirect(
            uri.copy(scheme = "https"),
            StatusCodes.PermanentRedirect
          )
        case _ =>
          route
      }
    }
  }

  /**
    * If not satisfying cond, Future is failed
    * @param cond
    * @param exception
    * @return
    */
  def requireFuture(cond: Boolean, exception: => Exception): Future[Unit] = {
    if(cond){
      Future.successful(())
    } else {
      Future.failed(exception)
    }
  }

  /**
    * Generate stack trace string
    * (from: https://alvinalexander.com/scala/how-convert-stack-trace-exception-string-print-logger-logging-log4j-slf4j)
    * @param e
    * @return
    */
  def getStackTraceString(e: Throwable): String = {
    val sw = new StringWriter()
    e.printStackTrace(new PrintWriter(sw))
    sw.toString
  }
}
