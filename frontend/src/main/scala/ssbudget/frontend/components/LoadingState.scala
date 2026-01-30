package ssbudget.frontend.components

enum LoadingState[+T] {
  case Loading
  case Loaded(data: T)
  case Error(message: String)

  def map[U](f: T => U): LoadingState[U] = this match {
    case Loading      => Loading
    case Loaded(data) => Loaded(f(data))
    case Error(msg)   => Error(msg)
  }

  def flatMap[U](f: T => LoadingState[U]): LoadingState[U] = this match {
    case Loading      => Loading
    case Loaded(data) => f(data)
    case Error(msg)   => Error(msg)
  }

  def getOrElse[U >: T](default: => U): U = this match {
    case Loaded(data) => data
    case _            => default
  }

  def isLoading: Boolean = this == Loading
  def isLoaded: Boolean  = this match {
    case Loaded(_) => true
    case _         => false
  }
  def isError: Boolean   = this match {
    case Error(_) => true
    case _        => false
  }
}
