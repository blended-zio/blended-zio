package blended.zio.core.json

import zio._

import argonaut._

object JsonSupport {

  def extract(from: Json, path: String*): ZIO[Any, Throwable, Json] =
    extract(from, path.toList)

  def extract(from: Json, path: List[String]): ZIO[Any, Throwable, Json] =
    ZIO.fromEither(doExtract(from, path, List.empty)).mapError(s => new Exception(s))

  def attributes(json: Json): ZIO[Any, Throwable, Map[String, Json]] =
    ZIO.effect(json.assoc.getOrElse(List.empty).toMap)

  private def doExtract(from: Json, path: List[String], consumed: List[String]): Either[String, Json] = path match {
    case Nil     => Right(from)
    case x :: xs =>
      from.field(x) match {
        case None    =>
          val cPath = (x :: consumed).reverse.mkString(" / ")
          Left(s"Path [$cPath] not found in [$from]")
        case Some(j) => doExtract(j, xs, x :: consumed)
      }
  }
}
