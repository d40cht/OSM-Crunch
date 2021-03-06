TwoPi
=====

Automatic walking/cycling route generation from OSM maps


Build it using sbt:

./sbt "project routeSite" compile shell

Launch the app on localhost:8080 with:

(it will expect a route graph called 'default.bin.rg' in the repo route directory. I use a symlink to oxfordshire.bin.rg
for development)

./sbt "project routeSite" "container:start" shell

The OSMlib project has the framework for parsing and aggregating the various datasources and building the
routegraph (built as various applications). It also provides the algo for routing used by the web site.

The routeSite project has a Scalatra web backend, almost entirely serving JSON. Then in
routeSite/src/main/webapp/static you can find the majority of the webapp.

You can build a fat jar of the whole site for deployment (including all static html, js etc packed as resources)
using the command:

./sbt "project routeSite" assembly

The resultant jar will be assembled as:

./routeSite/target/scala-2.10/routeSite-assembly-0.0.1.jar

Notes
=====

* gdal_translate is very handy for transforming input datasets between various gis formats.


* Warp to WGS 84 (EPSG4326) using nearest-neighbour resampling: gdalwarp g100_06.tif -t_srs EPSG:4326 g100_06_EPSG4326.tif

* Extract to ASC: gdal_translate -projwin -15.0 64.0 5.0 48.0 g100_06_EPSG4326.tif -of AAIGrid g100_06_uk_EPSG4326.as
