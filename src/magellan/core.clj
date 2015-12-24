(ns magellan.core
  (:require [schema.core :as s]
            [clojure.java.io :as io])
  (:import (org.geotools.coverage.grid GridCoverage2D GridGeometry2D
                                       RenderedSampleDimension)
           (org.geotools.coverage.grid.io GridFormatFinder)
           (org.geotools.referencing CRS ReferencingFactoryFinder)
           (org.geotools.referencing.factory PropertyAuthorityFactory
                                             ReferencingFactoryContainer)
           (org.geotools.referencing.operation.projection MapProjection)
           (org.geotools.metadata.iso.citation Citations)
           (org.geotools.factory Hints)
           (org.geotools.geometry GeneralEnvelope)
           (org.geotools.coverage.processing Operations)
           (org.geotools.gce.geotiff GeoTiffWriter)
           (org.opengis.referencing.crs CoordinateReferenceSystem)
           (org.opengis.parameter GeneralParameterValue)
           (java.awt.image RenderedImage)))

(s/defrecord Raster
    [coverage   :- GridCoverage2D
     image      :- RenderedImage
     crs        :- CoordinateReferenceSystem
     projection :- (s/maybe MapProjection)
     envelope   :- GeneralEnvelope
     grid       :- GridGeometry2D
     width      :- Integer
     height     :- Integer
     bands      :- [RenderedSampleDimension]])

(s/defn to-raster :- Raster
  [coverage :- GridCoverage2D]
  (let [image (.getRenderedImage coverage)
        crs   (.getCoordinateReferenceSystem coverage)]
    (map->Raster
     {:coverage   coverage
      :image      image
      :crs        crs
      :projection (CRS/getMapProjection crs)
      :envelope   (.getEnvelope coverage)
      :grid       (.getGridGeometry coverage)
      :width      (.getWidth image)
      :height     (.getHeight image)
      :bands      (vec (.getSampleDimensions coverage))})))

(s/defn read-raster :- Raster
  [filename :- s/Str]
  (let [file (io/file filename)]
    (if (.exists file)
      (try (-> file
               (GridFormatFinder/findFormat)
               (.getReader file)
               (.read nil)
               (to-raster))
           (catch Exception e
             (println "Cannot read raster. Exception:" (class e))))
      (println "Cannot read raster. No such file:" filename))))

(s/defn srid-to-crs :- CoordinateReferenceSystem
  [srid-code :- s/Str]
  (CRS/decode srid-code))

(s/defn wkt-to-crs :- CoordinateReferenceSystem
  [wkt :- s/Str]
  (CRS/parseWKT wkt))

(s/defn crs-to-srid :- s/Str
  [crs :- CoordinateReferenceSystem]
  (CRS/lookupIdentifier crs true))

(s/defn crs-to-wkt :- s/Str
  [crs :- CoordinateReferenceSystem]
  (.toWKT crs))

(s/defn register-new-crs-definitions-from-properties-file! :- s/Any
  [authority-name :- s/Str
   filename       :- s/Str]
  (ReferencingFactoryFinder/addAuthorityFactory
   (PropertyAuthorityFactory.
    (ReferencingFactoryContainer.
     (Hints. Hints/CRS_AUTHORITY_FACTORY PropertyAuthorityFactory))
    (Citations/fromName authority-name)
    (io/resource filename)))
  (ReferencingFactoryFinder/scanForPlugins))

;; FIXME: Throws a NoninvertibleTransformException when reprojecting to EPSG:4326.
(s/defn reproject-raster :- Raster
  [raster :- Raster
   crs    :- CoordinateReferenceSystem]
  (to-raster (.resample Operations/DEFAULT (:coverage raster) crs)))

(s/defn resample-raster :- Raster
  [raster :- Raster
   grid   :- GridGeometry2D]
  (to-raster (.resample Operations/DEFAULT (:coverage raster) nil grid nil)))

(s/defn crop-raster :- Raster
  [raster   :- Raster
   envelope :- GeneralEnvelope]
  (to-raster (.crop Operations/DEFAULT (:coverage raster) envelope)))

;; FIXME: Throws NullPointerException.
(s/defn write-raster :- s/Any
  [raster   :- Raster
   filename :- s/Str]
  (let [writer (GeoTiffWriter. (io/file filename))
        params (->> writer
                    (.getFormat)
                    (.getWriteParameters)
                    (.values)
                    (into-array GeneralParameterValue))]
    (try (.write writer (:coverage raster) params)
         (catch Exception e
           (println "Cannot write raster. Exception:" (class e))))))

;;; ======================== Usage examples below here =============================

(comment

  (def fmod-iet (read-raster "/home/gjohnson/tmp/fuel_models/FMOD_IET_veg2015.tif"))
  (def fmod-reax (read-raster "/home/gjohnson/tmp/fuel_models/FMOD_REAX_v2005.tif"))
  (def lw-avg-20km (read-raster "/home/gjohnson/tmp/fuel_moisture_update/lw_avg_20km.tif"))
  (s/explain Raster)
  (s/validate Raster fmod-iet)
  (s/validate Raster fmod-reax)
  (s/validate Raster lw-avg-20km)
  (def fmod-iet-reprojected (reproject-raster fmod-iet (:crs fmod-reax)))
  (def fmod-iet-reprojected-and-cropped (crop-raster fmod-iet-reprojected (:envelope fmod-reax)))
  (s/validate Raster fmod-iet-reprojected)
  (s/validate Raster fmod-iet-reprojected-and-cropped)
  (register-new-crs-definitions-from-properties-file! "CALFIRE" "custom_projections.properties")
  )
