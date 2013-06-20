package org.seacourt.osm


import java.io.{File, BufferedInputStream, FileInputStream, FileOutputStream}
import java.util.zip._

import crosby.binary.osmosis.OsmosisReader

import org.openstreetmap.osmosis.core.task.v0_6.Sink
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer
import org.openstreetmap.osmosis.core.domain.v0_6

import scala.collection.{mutable, immutable}

import com.twitter.logging.Logger
import com.twitter.logging.{Logger, LoggerFactory, FileHandler, ConsoleHandler, Policy}
import com.twitter.logging.config._

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{ Input, Output }

import com.twitter.chill._

trait Logging
{
    lazy val log = Logger.get(getClass)
}

case class Tag( val keyId : Int, val valueId : Int )
{
    def this() = this(-1, -1)
}



class StringMap
{
    private var nextId = 0
    private val stringMap = mutable.Map[String, Int]()
    private val stringArray = mutable.ArrayBuffer[String]()
    
    def apply( s : String ) : Int =
    {
        stringMap.get(s) match
        {
            case Some(id)   => id
            case _          =>
            {
                val sId = nextId
                val cs = new String(s)
                stringArray.append( cs )
                stringMap.put( cs, sId )
                nextId += 1
                sId
            }
        }
    }
    
    def apply( id : Int ) = stringArray(id)
}

case class TagStringRegistry( val keyMap : StringMap, val valMap : StringMap )
{   
    def this() = this( new StringMap(), new StringMap() )
    def apply( key : String, value : String ) = new Tag( keyMap(key), valMap(value) )
}


case class Coord( val lon : Double, val lat : Double )
{
    def this() = this(0.0, 0.0)
}
case class Node( val coord : Coord, val tags : Array[Tag] )
{
    def this() = this( null, Array() )
}
case class Way( val id : Long, val nodes : Array[Node], val tags : Array[Tag] )
{
    def this() = this( -1, Array(), Array() )
}

case class OSMMap( val ways : Array[Way], val tagRegistry : TagStringRegistry )
{
    def this() = this( Array(), null )
}

object OSMMap extends Logging
{
    def save( map : OSMMap, fileName : File )
    {
        import java.io._
        import java.util.zip._
        
        log.info( "Serialising to: " + fileName )
        val kryo = new Kryo()
        
        val output = new Output( new GZIPOutputStream( new FileOutputStream( fileName ) ) )
        kryo.writeObject(output, map)
        output.close
        
        log.info( "Complete." )
    }
    
    def load( fileName : File ) : OSMMap =
    {
        log.info( "Reading map from disk." )
        val kryo = new Kryo()
        val input = new Input( new GZIPInputStream( new java.io.FileInputStream( fileName ) ) )
        val map = kryo.readObject( input, classOf[OSMMap] )
        log.info( "Number of ways: " + map.ways.size )
        
        map
    }
}

class CrunchSink extends Sink with Logging
{
    import scala.collection.JavaConversions._
    
    def inUk( c : Coord ) = c.lon > -9.23 && c.lon < 2.69 && c.lat > 49.84 && c.lat < 60.85
        
    var ukNodes = 0
    var ukWays = 0
    var ukWayNodes = 0
    
    def initialize(metaData : java.util.Map[String, Object])
    {
        println( "initialize" )
    }
    
    val nodesById = mutable.Map[Long, Node]()
    val wayNodeSet = mutable.Set[Long]()
    
    val ways = mutable.ArrayBuffer[Way]()
    val tsr = new TagStringRegistry()
    
    def process(entityContainer : EntityContainer)
    {
        val entity = entityContainer.getEntity()
        
        entity match
        {
            case n : v0_6.Node =>
            {
                val c = new Coord( n.getLongitude(), n.getLatitude() )
                
                if ( inUk(c) )
                {
                    ukNodes += 1
                    if ( (ukNodes % 100000) == 0 ) log.info( "Nodes: " + ukNodes.toDouble / 1000000.0 + "M" )
                    
                    val nodeTags = n.getTags().map { t => tsr( t.getKey(), t.getValue() ) }.toArray
                    //val nodeTags = Array[Tag]()
                    
                    nodesById.put( n.getId(), Node( c, nodeTags ))
                }
            }
            
            case w : v0_6.Way =>
            {
                val nodeIds : Array[Long] = w.getWayNodes().map( _.getNodeId() ).toArray
                
                val haveAllNodes = nodeIds.forall( nid => nodesById contains nid )
                val wayTags = w.getTags().map { t => ( t.getKey(), t.getValue() ) }.toMap
                if ( haveAllNodes && (wayTags contains "highway") )
                {
                    // Tag: highway=* or junction=*
                    nodeIds.foreach( nid => wayNodeSet.add(nid) )
                    val nodes = nodeIds.map( nid => nodesById(nid) )
                    ukWays += 1
                    ukWayNodes += nodeIds.length
                    if ( (ukWays % 10000) == 0 ) log.info( "Ways: " + ukWays.toDouble / 1000000.0 + "M" + ", " + ukWayNodes.toDouble / 1000000.0 + "M" )
                    val way = Way( w.getId(), nodes, wayTags.toArray.map( t => tsr(t._1, t._2) ) )
                    ways.append(way)
                }
            }
            
            // TODO: And relations please
            
            case _ =>
        }
    }
    
    def getData() = new OSMMap( ways.toArray, tsr )
    
    def complete()
    {
        log.info( "complete" )
    }
    
    def release()
    {
        log.info( "release" )
    }
}

class OSMCrunch( val dataFileName : File )
{
    import java.io._
    
    def run() : OSMMap =
    {
        val osmMap = 
        {
            val reader = new OsmosisReader( new BufferedInputStream( new FileInputStream( dataFileName ) ) )
            val cs = new CrunchSink()
            reader.setSink( cs )   
            reader.run()
            cs.getData()
        }
        
        osmMap
        
    }
}

object OSMCrunch extends App
{    
    override def main( args : Array[String] )
    {
        Logger.clearHandlers()
        LoggerFactory( node="org.seacourt", handlers = List(ConsoleHandler( level = Some( Level.INFO ) )) ).apply()
        
        val f = "oxfordshire-latest.osm.pbf"
        //val f = "great-britain-latest.osm.pbf"
        //val f = "europe-latest.osm.pbf"
       
        val mapFile = new File( args(1) )
        
        { 
            val map =
            {
                val osmc = new OSMCrunch( new File(args(0)) )
                osmc.run()
            }
            
            OSMMap.save( map, mapFile )
        }
        
        val loadedMap = OSMMap.load( mapFile )
    }
}

class MapWithIndex( val map : OSMMap )
{
    import com.infomatiq.jsi.{Rectangle, Point}
    import com.infomatiq.jsi.rtree._
    
    private val index =
    {
        val t = new RTree()
        t.init(null)

        map.ways.zipWithIndex.foreach
        { case (w, wi) =>
        
            w.nodes.foreach
            { n=>
                
                //val env = new Envelope( new Coordinate( n.coord.lon, n.coord.lat ) )
                //t.insert( env, w )
                t.add( new Rectangle( n.coord.lon.toFloat, n.coord.lat.toFloat, n.coord.lon.toFloat, n.coord.lat.toFloat ), wi )
            }
        }
        
        t
    }
    
    def get( c : Coord, n : Int  ) : Seq[Way] =
    {
        val wis = mutable.ArrayBuffer[Int]()
        
        index.nearestN(
            new Point( c.lon.toFloat, c.lat.toFloat ),
            new gnu.trove.TIntProcedure
            {
                def execute( wi : Int ) =
                {
                    wis.append(wi)
                    true
                }
            },
            n,
            Float.MaxValue )
            
        wis.map( wi => map.ways(wi) )
    }
    
    implicit class RichTag( val tag : Tag )
    {
        def key = map.tagRegistry.keyMap( tag.keyId )
        def value = map.tagRegistry.valMap( tag.valueId )
    }
}

object CalculateWayLength extends App with Logging
{
    import com.vividsolutions.jts.geom.{Coordinate, Envelope}
    
    def distFrom(lat1 : Double, lng1 : Double, lat2 : Double, lng2 : Double ) : Double =
    {
        val earthRadius = 3958.75;
        val dLat = Math.toRadians(lat2-lat1)
        val dLng = Math.toRadians(lng2-lng1)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                   Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                   Math.sin(dLng/2) * Math.sin(dLng/2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
        val dist = earthRadius * c

        val meterConversion = 1609

        dist * meterConversion
    }

    
    override def main( args : Array[String] )
    {
        Logger.clearHandlers()
        LoggerFactory( node="org.seacourt", handlers = List(ConsoleHandler( level = Some( Level.INFO ) )) ).apply()
        
        val loadedMap = OSMMap.load( new File( args(0) ) )
     
        var acc = 0.0
        var segments = 0

        for ( (w, i) <- loadedMap.ways.zipWithIndex )
        {
            if ( (i % 1000) == 0 ) log.info( "Adding way: " + i )
           
            for ( Array(n1, n2) <- w.nodes.sliding(2) )
            {
                acc += distFrom( n1.coord.lat, n1.coord.lon, n2.coord.lat, n2.coord.lon )
                segments +=1
                
            }
        }
        
        log.info( "Total distance: " + acc + ", average segment length: " + (acc/segments.toDouble) )
    }
}

