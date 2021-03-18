package blended.zio.jolokia

import java.net.URLEncoder

final case class MBeanSearchDef(
  jmxDomain: String,
  searchProperties: Map[String, String] = Map.empty
) {

  lazy val operation =
    s"search/${URLEncoder.encode(jmxDomain, "UTF-8")}:${URLEncoder.encode(pattern, "UTF-8")}*"

  lazy val pattern = searchProperties match {
    case m if m.isEmpty => ""
    case m              => m.keys.map(k => s"$k=${m.get(k).get}").mkString("", ",", ",")
  }

}

final case class OperationExecDef(
  objectName: String,
  operationName: String,
  parameters: List[String] = List.empty
) {
  val pattern = s"$objectName/$operationName/" + parameters.mkString("/")
}
