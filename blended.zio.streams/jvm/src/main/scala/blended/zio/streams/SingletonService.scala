package blended.zio.streams

import zio._

private[streams] trait SingletonService[A] {

  private[streams] val instance: Ref[Option[A]]
  private[streams] def makeService: ZIO[Any, Nothing, A]

  private[streams] def service: ZIO[Any, Nothing, A] = for {
    sr  <- instance.get
    svc <- sr match {
             case Some(s) => ZIO.succeed(s)
             case None    =>
               for {
                 s <- makeService
                 _ <- instance.set(Some(s))
               } yield s
           }
  } yield (svc)
}

object SingletonService {
  def fromEffect[A](e: ZIO[Any, Nothing, A]): ZIO[Any, Nothing, SingletonService[A]] = for {
    inst <- Ref.make[Option[A]](None)
    svc   = new SingletonService[A] {
              override private[streams] val instance    = inst
              override private[streams] def makeService = e
            }
  } yield (svc)
}
