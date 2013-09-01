package org.seacourt.routeSite

import org.scalatra._
import scalate.ScalateSupport

import org.seacourt.osm.{OSMMap, Coord}
import org.seacourt.osm.route.{RoutableGraph, RouteNode}

// In sbt:
//
// > container:start
// > ~ ;copy-resources;aux-compile

class RouteGraphHolder
{
    //val mapFile = new java.io.File( "./uk.bin" )
    val mapFile = new java.io.File( "./oxfordshire.bin" )
    val map = OSMMap.load( mapFile )
    val rg = RoutableGraph( map )
}
// Weird cost:
// http://localhost:8080/displayroute?lon=-3.261151337280192&lat=54.45527013007099&distance=30.0&seed=1
class RouteSiteServlet extends ScalatraServlet with ScalateSupport
{
    var rghOption : Option[RouteGraphHolder] = None
    
    def template( pageName : String )( sideBar : scala.xml.Elem )( pageBody : scala.xml.Elem ) =
    {
        <html>
            <head>
                <title>{pageName}</title>
                <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
                
                <link href="css/bootstrap.min.css" rel="stylesheet" media="screen"/>
                <link href="css/bootstrap-responsive.css" rel="stylesheet"/>
                <style>
                  body {{
                    padding-top: 60px; /* 60px to make the container go all the way to the bottom of the topbar */
                  }}
                </style>
            </head>
            <body>

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

                <div class="container-fluid">

                    <div class="span2">
                    {
                        sideBar
                    }
                    </div>
                    
                    <div class="span10">
                    {
                        pageBody
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
    }
  
    get("/hello-scalate")
    {
        template("Thank-you!")
        {
            <h3>Sidebar</h3>
        }
        {
            <div>
                <h1>Why, thank-you.</h1>
            </div>
        }
    }
    
    get("/displayroute")
    {
        val lon = params.getOrElse("lon","-1.361461").toDouble
        val lat = params.getOrElse("lat", "51.709").toDouble
        val distInKm = params.getOrElse("distance", "30.0").toDouble
        val seed = params.getOrElse("seed", "1").toInt
        
        val onLoad = "init( %f, %f, 12, '/route?lon=%f&lat=%f&distance=%f&seed=%d' );".format( lon, lat, lon, lat, distInKm, seed )
        
        template( "Display route" )
        {
            <h3>Sidebar</h3>
        }
        {
            <html>
            <head>
                <!-- Source: http://wiki.openstreetmap.org/wiki/Openlayers_Track_example -->
                <title>Simple OSM GPX Track</title>
                <script src="http://www.openlayers.org/api/OpenLayers.js"></script>
                <script src="http://www.openstreetmap.org/openlayers/OpenStreetMap.js"></script>
                <script src="http://ajax.googleapis.com/ajax/libs/jquery/1.10.2/jquery.min.js"></script>
                <script src="/js/osmmap.js"></script>
             
            </head>
            <!-- body.onload is called once the page is loaded (call the 'init' function) -->
            <body onload={onLoad}>
                <!-- define a DIV into which the map will appear. Make it take up the whole window -->
                <div style="width:100%; height:80%" id="map"></div>
                <div style="text-align:center">
                    <form action="/displayroute" method="get">
                        Longitude: <input name="lon" id="lon" type="text" value={lon.toString}></input>
                        Latitude: <input name="lat" id="lat" type="text" value={lat.toString}></input>
                        Distance (km): <input name="distance" type="text" value={distInKm.toString}></input>
                        Seed: <input name="seed" type="text" value={(seed+1).toString}></input>
                        <input type="submit" value="Generate route"/>
                    </form>
                </div>
            </body>
            </html>
        }
    }
    
    // Render to: http://www.darrinward.com/lat-long/
    // e.g. http://localhost:8080/route?data=-1.3611464,51.7094267,50.0,3
    get("/route")
    {
        contentType="text/xml"
        
        if ( rghOption.isEmpty ) rghOption = Some( new RouteGraphHolder() )
        
        val rgh = rghOption.get
        
        val lon = params("lon").toDouble
        val lat = params("lat").toDouble
        val distInKm = params("distance").toDouble
        val seed = params("seed").toInt
        
        val startCoords = Coord(lon, lat)
        
        println( "Finding closest node..." )
        val closestNode = rgh.rg.getClosest( startCoords )
        
        println( "Closest: " + closestNode.node.coord )
        
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
                            case Some( lrn ) => lrn.node.coord.distFrom( rn.node.coord )
                            case None => 0.0
                        }
                        cumDistance += dist
                        
                        val res = <trkpt lat={rn.node.coord.lat.toString} lon={rn.node.coord.lon.toString} distance={cumDistance.toString}/>
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
                    
                    <pic lon={pic.coord.lon.toString} lat={pic.coord.lat.toString} link={pic.link}/>
                }
            }
            </pics>
        </gpx>
    }
}

