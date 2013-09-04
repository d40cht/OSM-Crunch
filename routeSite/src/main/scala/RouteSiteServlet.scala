package org.seacourt.routeSite

import scala.collection.{mutable, immutable}

import org.scalatra._
import scalate.ScalateSupport

import org.seacourt.osm.{OSMMap, Coord, Logging}
import org.seacourt.osm.route.{RoutableGraph, RouteNode, RTreeIndex, ScenicPoint}

import org.eclipse.jetty.server.Server
import org.eclipse.jetty.servlet.{DefaultServlet, ServletContextHandler}
import org.eclipse.jetty.webapp.WebAppContext
import org.scalatra.servlet.ScalatraListener

// In sbt:
//
// > container:start
// > ~ ;copy-resources;aux-compile

class RouteGraphHolder
{
    val rg = RoutableGraph.load( new java.io.File( "./default.bin.rg" ) )
}
// Weird cost:
// http://localhost:8080/displayroute?lon=-3.261151337280192&lat=54.45527013007099&distance=30.0&seed=1
class RouteSiteServlet extends ScalatraServlet with ScalateSupport with Logging
{
    import net.sf.ehcache.{CacheManager, Element}

    private var rghOption : Option[RouteGraphHolder] = None
    
    private def getRGH =
    {
        if ( rghOption.isEmpty ) rghOption = Some( new RouteGraphHolder() )
        rghOption.get
    }
    
    CacheManager.getInstance().addCache("memoized")
   
    
    def cached[T](name : String, args : Any* )( body : => T ) =
    {
        import java.security.MessageDigest
        

        def md5(s: String) : String =
        {
            MessageDigest.getInstance("SHA").digest(s.getBytes).map( x => "%02x".format(x) ).mkString
        }
        
        val hash = md5( name + "_" + List(args:_*).map( _.toString ).mkString("_") ).toString
        
        val cache = CacheManager.getInstance().getCache("memoized")   
        try
        {
            cache.acquireWriteLockOnKey( hash )
            
            val el = cache.get(hash)
            if ( el != null )
            {
                log.info( "Cached element found" )
                
                el.getObjectValue.asInstanceOf[T]
            }
            else
            {
                log.info( "Cached element not found - running function" )
                val result = body
                
                cache.put( new Element( hash, result ) )
                
                result
            }
        }
        finally
        {
            cache.releaseWriteLockOnKey( hash )
        }
    }
    
    private def getRouteXML( lon : Double, lat : Double, distInKm : Double, seed : Int ) = cached[scala.xml.Node]("routeXML", lon, lat, distInKm, seed )
    {
        import net.liftweb.json.{JsonParser, DefaultFormats, JObject}
        import scalaj.http.{Http, HttpOptions}
        implicit val formats = DefaultFormats
        
        val rgh = getRGH
        val startCoords = Coord(lon, lat)
        
        log.info( "Finding closest node..." )
        val closestNode = rgh.rg.getClosest( startCoords )
        
        log.info( "Closest: " + closestNode.coord )
        
        val route = rgh.rg.buildRoute( closestNode, distInKm * 1000.0, seed )
        val routeNodes = route.routeNodes
        val pics = route.picList
        
        var lastRN : Option[RouteNode] = None
        var cumDistance = 0.0
        <gpx>
            <trk>
                <name>Example route</name>
                <trkseg>
                {
                    routeNodes.map
                    { rn =>
                    
                        val dist = lastRN match
                        {
                            case Some( lrn ) => lrn.coord.distFrom( rn.coord )
                            case None => 0.0
                        }
                        cumDistance += dist
                        
                        val res = <trkpt lat={rn.coord.lat.toString} lon={rn.coord.lon.toString} distance={cumDistance.toString} ele={rn.height.toString}/>
                        lastRN = Some( rn )
                        res
                    }
                }
                </trkseg>
            </trk>
            <pics>
            {
                pics.map
                { pic =>

                    val picIndex = pic.picIndex
                    
                    val resJSON = Http("http://jam.geograph.org.uk/sample8.php?q=&select=title,grid_reference,realname,user_id,hash&range=%d,%d".format( picIndex, picIndex ))
                        .option(HttpOptions.connTimeout(5000))
                        .option(HttpOptions.readTimeout(5000))
                    { inputStream => 
                        JsonParser.parse(new java.io.InputStreamReader(inputStream))
                    }
                    
                    val imgMatches = (resJSON \\ "matches")
                    val imgMetaData = imgMatches.asInstanceOf[JObject].obj.head.value
    
                    val title = (imgMetaData \\ "title").extract[String]
                    val authorName = (imgMetaData \\ "realname").extract[String]
                    val hash = (imgMetaData \\ "hash").extract[String]
                    
                    val imageUrl = imgUrl( picIndex, hash )
                    
                    val link = "http://www.geograph.org.uk/photo/" + pic.picIndex
                    
                    <pic lon={pic.coord.lon.toString} lat={pic.coord.lat.toString} img={imageUrl} link={link} title={title} author={authorName}/>
                }
            }
            </pics>
        </gpx>
    }
    
    def template( pageName : String, onBodyLoad : Option[String] = None )( sideBarLeft : scala.xml.Elem )( pageBody : scala.xml.Elem )( sideBarRight : scala.xml.Elem ) =
    {
        <html>
            <head>
                <title>{pageName}</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                
                <link href="css/bootstrap.min.css" rel="stylesheet" media="screen"/>
                <link href="css/bootstrap-responsive-min.css" rel="stylesheet"/>
                
                <script src="http://www.openlayers.org/api/OpenLayers.js"></script>
                <script src="http://www.openstreetmap.org/openlayers/OpenStreetMap.js"></script>
                <script src="http://ajax.googleapis.com/ajax/libs/jquery/1.10.2/jquery.min.js"></script>
                <script src="/js/osmmap.js"></script>

                <style>
                  body {{
                    padding-top: 60px; /* 60px to make the container go all the way to the bottom of the topbar */
                    padding-left: 10px;
                    padding-right: 10px;
                  }}
                </style>
                
            </head>
            <body onLoad={onBodyLoad.map( s => scala.xml.Text(s) )} style="height:90%">

                <div class="navbar navbar-inverse navbar-fixed-top">
                  <div class="navbar-inner">
                    <div class="container">
                      <button type="button" class="btn btn-navbar" data-toggle="collapse" data-target=".nav-collapse">
                        <span class="icon-bar"></span>
                        <span class="icon-bar"></span>
                        <span class="icon-bar"></span>
                      </button>
                      <a class="brand" href="/">Routility</a>
                      <div class="nav-collapse collapse">
                        <ul class="nav">
                          <li class="active"><a href="/">Home</a></li>
                          <li><a href="/about">About</a></li>
                          <li><a href="/contact">Contact</a></li>
                        </ul>
                      </div><!--/.nav-collapse -->
                    </div>
                  </div>
                </div>

                <div class="row-fluid">

                    <div class="span2" style="height:100%; overflow-y: scroll; overflow-x: hidden">
                    {
                        sideBarLeft
                    }
                    </div>
                    
                    <div class="span8" style="height:100%; overflow: auto">
                    {
                        pageBody
                    }
                    </div>
                    
                    <div class="span2">
                    {
                        sideBarRight
                    }
                    </div>

                </div>
              </body>
        </html>
    }

    get("/")
    {
        template("Routility")
        {
            <h3>Sidebar</h3>
        }
        {
            <div>
                <h1>Main page</h1>
                
                <a href="/displayroute">Navigate to navigate.</a>
            </div>
        }
        {
            <h3>Sidebar</h3>
        }
    }
  
    
    private def imgUrl( index : Long, hash : String ) : String =
    {
        val yz = index / 1000000
        val ab = (index % 1000000) / 10000
        val cd = (index % 10000) / 100
        
        if ( yz == 0 )
        {
            "http:///s0.geograph.org.uk/photos/%02d/%02d/%06d_%s.jpg".format( ab, cd, index, hash )
        }
        else
        {
            "http:///s0.geograph.org.uk/geophotos/%02d/%02d/%02d/%06d_%s.jpg".format( yz, ab, cd, index, hash )
        }
    }
    
    
    get("/displayroute")
    {
        val lon = params.getOrElse("lon","-1.361461").toDouble
        val lat = params.getOrElse("lat", "51.709").toDouble
        val distInKm = params.getOrElse("distance", "30.0").toDouble
        val seed = params.getOrElse("seed", "1").toInt
        
        val onLoad = "init( %f, %f, 12, '/route?lon=%f&lat=%f&distance=%f&seed=%d' );".format( lon, lat, lon, lat, distInKm, seed )
        
        val xmlData = getRouteXML( lon, lat, distInKm, seed )
        
        template( "Display route", onBodyLoad=Some(onLoad) )
        {
            <div>
                {
                    val pics = xmlData \\ "pic"
                    pics.map
                    { p =>

                        val fullLink = (p \ "@link").text
                        val imageUrl = (p \ "@img").text
                        val title = (p \ "@title").text
                        val credits = "Copyright %s and licensed for reuse under the Creative Commons Licence.".format( (p \ "@author").text )
                        
                        <a href={fullLink}><img src={imageUrl} alt={credits} title={credits}/></a>
                        <br/>
                        <div>{title}</div>
                        <br/>
                    }
                }
            </div>
        }
        {
            <div>
                <!-- define a DIV into which the map will appear. Make it take up the whole window -->
                <!--<div style="width:100%; height:80%" id="map"></div>-->
                <div id="map"></div>
                
                <!-- Add elevation chart at bottom from highcharts, e.g. http://www.highcharts.com/demo/line-ajax -->
                <div style="width:100%; height:20%" id="elevation"></div>
                
            </div>
        }
        {
            <div style="text-align:center">
                <form action="/displayroute" method="get">
                    <table>
                        <tr>
                            <td>Longitude:</td>
                            <td><input name="lon" id="lon" type="text" value={lon.toString}></input></td>
                        </tr>
                        <tr>
                            <td>Latitude:</td>
                            <td><input name="lat" id="lat" type="text" value={lat.toString}></input></td>
                        </tr>
                        <tr>
                            <td>Distance (km):</td>
                            <td><input name="distance" type="text" value={distInKm.toString}></input></td>
                        </tr>
                        <tr>
                            <td>Seed:</td>
                            <td><input name="seed" type="text" value={(seed+1).toString}></input></td>
                        </tr>
                    </table>
                    <input type="submit" value="Generate route"/>
                    
                </form>
            </div>
        }
    }
    
    
    // Render to: http://www.darrinward.com/lat-long/
    // e.g. http://localhost:8080/route?data=-1.3611464,51.7094267,50.0,3
    
    // Embed route data in page in <script id="foo" type="text/xmldata"> tag?
    get("/route")
    {
        contentType="text/xml"
        
        val rgh = getRGH
        
        val lon = params("lon").toDouble
        val lat = params("lat").toDouble
        val distInKm = params("distance").toDouble
        val seed = params("seed").toInt
        
        getRouteXML( lon, lat, distInKm, seed )
    }
}

object JettyLauncher { // this is my entry object as specified in sbt project definition
  def main(args: Array[String]) {
    val port = if(System.getenv("PORT") != null) System.getenv("PORT").toInt else 8080

    val server = new Server(port)
    val context = new WebAppContext()
    context setContextPath "/"
    //context.setResourceBase("src/main/webapp")
    val resourceBase = getClass.getClassLoader.getResource("webapp").toExternalForm
    context.setResourceBase(resourceBase)
    context.addEventListener(new ScalatraListener)
    context.addServlet(classOf[DefaultServlet], "/")

    server.setHandler(context)

    server.start
    server.join
  }
}

