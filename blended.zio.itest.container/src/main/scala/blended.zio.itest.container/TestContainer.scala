package blended.zio.itest.container

import com.dimafeng.testcontainers.GenericContainer

import zio._
import zio.blocking._

trait TestContainer[T <: GenericContainer] {

  def container: T

  def managed = ZManaged.make {
    effectBlocking {
      container.start()
      container
    }.orDie
  } { container =>
    effectBlocking {
      container.stop()
    }.orDie
  }

  def layer(implicit tag: Tag[T]) = managed.toLayer
}

object TestContainer {

  def managed[T <: GenericContainer](implicit tc: TestContainer[T]): ZManaged[Blocking, Nothing, T] =
    tc.managed

  def layer[T <: GenericContainer](implicit tc: TestContainer[T], tag: Tag[T]): ZLayer[Blocking, Nothing, Has[T]] =
    tc.layer
}
