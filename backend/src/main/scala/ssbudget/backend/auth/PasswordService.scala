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

    override def hash(password: String): IO[String] = IO.blocking {
      argon2.hash(10, 65536, 1, password.toCharArray)
    }

    override def verify(password: String, hash: String): IO[Boolean] = IO.blocking {
      argon2.verify(hash, password.toCharArray)
    }
  }
}
