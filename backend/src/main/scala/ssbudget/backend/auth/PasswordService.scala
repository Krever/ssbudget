package ssbudget.backend.auth

import cats.effect.IO
import de.mkammerer.argon2.{Argon2, Argon2Factory}

trait PasswordService {
  def hash(password: String): IO[String]
  def verify(password: String, hash: String): IO[Boolean]
}

object PasswordService {

  def apply(): PasswordService = new PasswordServiceImpl()

  private class PasswordServiceImpl extends PasswordService {
    private val argon2: Argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)

    // One hash takes 64 MB of native memory. Two at a time is the limit, so that a burst of login requests cannot use up the memory of the machine.
    private val gate = new java.util.concurrent.Semaphore(2)

    private def gated[A](work: => A): IO[A] = IO.blocking {
      gate.acquire()
      try work
      finally gate.release()
    }

    override def hash(password: String): IO[String] = gated(argon2.hash(10, 65536, 1, password.toCharArray))

    override def verify(password: String, hash: String): IO[Boolean] = gated(argon2.verify(hash, password.toCharArray))
  }
}
