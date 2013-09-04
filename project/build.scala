import sbt._
import Keys._
import org.scalatra.sbt._
import org.scalatra.sbt.PluginKeys._
import com.mojolly.scalate.ScalatePlugin._
import ScalateKeys._
import sbtassembly.Plugin._
import AssemblyKeys._


object Toplevel extends Build
{
    lazy val scalatraVersion = "2.2.1"
    
    lazy val commonSettings = Defaults.defaultSettings ++ Seq(
        scalaVersion    := "2.10.2",
        version         := "0.0.1",
        organization := "org.seacourt",
        scalacOptions   ++= Seq( "-deprecation", "-Xlint", "-optimize" ),
        resolvers       ++= Seq(
            Resolver.sonatypeRepo("snapshots"),
            "sourceforge jsi repository" at "http://sourceforge.net/projects/jsi/files/m2_repo",
            "maven2 dev repository" at "http://download.java.net/maven/2",
            "osgeo" at "http://download.osgeo.org/webdav/geotools"
        ),
        libraryDependencies ++= Seq(
            "com.twitter" %% "util-logging" % "6.3.6",
            "org.xeustechnologies" % "jtar" % "1.1",
            "com.twitter" %% "chill" % "0.2.3",
            "org.openstreetmap.osmosis" % "osmosis-pbf" % "0.43-RELEASE",
            "com.vividsolutions" % "jts" % "1.13",
            "net.sourceforge.jsi" % "jsi" % "1.0.0",
            "org.scalaz" % "scalaz-core_2.10" % "7.0.3",
            "org.scalatest" %% "scalatest" % "1.9.1" % "test"
        )
    )
    
    lazy val OSMlib = Project( id="OSMlib", base=file("OSMlib"),
        settings=commonSettings
    )
    
    lazy val routeSite = Project( id="routeSite", base=file("routeSite"),
        settings=commonSettings ++ assemblySettings ++ ScalatraPlugin.scalatraWithJRebel ++ scalateSettings ++ Seq(
            libraryDependencies ++= Seq(
                "javax.transaction" % "jta" % "1.1",
                "net.sf.ehcache" % "ehcache" % "2.7.2",
                "org.scalaj" %% "scalaj-http" % "0.3.9" exclude("junit", "junit"),
                "net.liftweb" %% "lift-json" % "2.5.1",
                "org.scalatra" %% "scalatra" % scalatraVersion,
                "org.scalatra" %% "scalatra-scalate" % scalatraVersion,
                "org.scalatra" %% "scalatra-specs2" % scalatraVersion % "test",
                "ch.qos.logback" % "logback-classic" % "1.0.6" % "runtime",
                "org.eclipse.jetty" % "jetty-webapp" % "8.1.8.v20121106" % "container;compile",
                "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))
            ),
            scalateTemplateConfig in Compile <<= (sourceDirectory in Compile){ base =>
                Seq(
                    TemplateConfig(
                        base / "webapp" / "WEB-INF" / "templates",
                        Seq.empty, /* default imports should be added here */
                        Seq(
                            Binding("context", "_root_.org.scalatra.scalate.ScalatraRenderContext", importMembers = true, isImplicit = true)
                        ), /* add extra bindings here */
                        Some("templates")
                    )
                )
            },
            mergeStrategy in assembly <<= (mergeStrategy in assembly) { (old) =>
            {
                case "com/esotericsoftware/minlog/Log$Logger.class" => MergeStrategy.first
                case "com/esotericsoftware/minlog/Log.class" => MergeStrategy.first
                case "osmosis-plugins.conf" => MergeStrategy.first
                case "about.html" => MergeStrategy.first
                case x  => old(x)
            } },
            resourceGenerators in Compile <+= (resourceManaged, baseDirectory) map
            { (managedBase, base) =>
            
                // Copy the resources into managedBase where package+assembly tasks can find them
                val webappBase = base / "src" / "main" / "webapp"
                for {
                    (from, to) <- webappBase ** "**" x rebase(webappBase, managedBase / "main" / "webapp")
                }
                yield
                {
                    Sync.copy(from, to)
                    to
                }
          }
        )
    )
    .dependsOn( OSMlib )
        
}

