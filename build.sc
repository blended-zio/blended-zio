import mill._
import scalalib._

import os.Path

object Deps {
  val argonaut       = ivy"io.argonaut::argonaut:6.3.6"
  val sttp3Core      = ivy"com.softwaremill.sttp.client3::core:3.3.13"
  val zio            = ivy"dev.zio::zio:2.0.0-M6-2"
}

object blended  extends Module { 

  val rootPath =  millSourcePath / (os.up)

  trait BlendedModule extends SbtModule {
    def name : String
    override def scalaVersion = "3.1.0"

    override def artifactName = T { s"blended.zio.$name" }
    override def millSourcePath: Path = rootPath / s"blended.zio.${name}"

    object test extends super.Tests
  }

  object core extends BlendedModule {
    override def name = "core"
    override def ivyDeps = T { Agg( Deps.zio, Deps.argonaut, Deps.sttp3Core) }
  }
  
  object solace extends BlendedModule {
    override def name: String = "solace"
    override def ivyDeps = T { Agg( Deps.zio ) }

  }
} 
