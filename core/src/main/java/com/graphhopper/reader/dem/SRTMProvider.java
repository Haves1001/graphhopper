/*
 *  Licensed to Peter Karich under one or more contributor license
 *  agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  Peter Karich licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the
 *  License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader.dem;

import com.graphhopper.storage.DataAccess;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.util.BitUtil;
import com.graphhopper.util.Downloader;
import com.graphhopper.util.Helper;
import gnu.trove.map.hash.TIntObjectHashMap;
import java.io.*;
import java.util.zip.ZipInputStream;

/**
 * Elevation data from NASA (SRTM). Downloaded from http://dds.cr.usgs.gov/srtm/version2_1/SRTM3/
 * <p>
 * Important information about SRTM: the coordinates of the lower-left corner of tile N40W118 are 40
 * degrees north latitude and 118 degrees west longitude. To be more exact, these coordinates refer
 * to the geometric center of the lower left sample, which in the case of SRTM3 data will be about
 * 90 meters in extent.
 * <p>
 * @author Peter Karich
 */
public class SRTMProvider implements ElevationProvider
{
    public static void main( String[] args ) throws IOException
    {
        new SRTMProvider().getEle(52.882391, 4.63623);
    }

    private static final BitUtil BIT_UTIL = BitUtil.BIG;
    private final int WIDTH = 1201;
    private Directory dir;
    private Downloader downloader = new Downloader("GraphHopper SRTMReader");
    private File cacheDir = new File("/tmp");
    // use a map as an array is not quite useful if we want to hold only parts of the world
    private final TIntObjectHashMap<HeightTile> cacheData = new TIntObjectHashMap<HeightTile>();
    private final TIntObjectHashMap<String> areas = new TIntObjectHashMap<String>();

    public SRTMProvider()
    {        
        // move to explicit calls?
        init();
        dir = new RAMDirectory();
    }

    /**
     * The URLs are a bit ugly and so we need to find out which area name a certain lat,lon
     * coordinate has.
     */
    private SRTMProvider init()
    {
        try
        {
            String strs[] =
            {
                "Africa", "Australia", "Eurasia", "Islands", "North_America", "South_America"
            };
            for (String str : strs)
            {
                InputStream is = getClass().getResourceAsStream(str + "_names.txt.zip");
                ZipInputStream zis = new ZipInputStream(is);
                zis.getNextEntry();
                for (String line : Helper.readFile(new InputStreamReader(zis, "UTF-8")))
                {
                    int lat = Integer.parseInt(line.substring(1, 3));
                    if (line.substring(0, 1).charAt(0) == 'S')
                        lat = -lat;

                    int lon = Integer.parseInt(line.substring(4, 7));
                    if (line.substring(3, 4).charAt(0) == 'W')
                        lon = -lon;

                    int intKey = calcIntKey(lat, lon);
                    String key = areas.put(intKey, str);
                    if (key != null)
                        throw new IllegalStateException("do not overwrite existing! key " + intKey + " " + key + " vs. " + str);
                }
            }
            return this;
        } catch (Exception ex)
        {
            throw new IllegalStateException("Cannot load area names from classpath", ex);
        }
    }

    // use int key instead of string for lower memory usage
    private int calcIntKey( double lat, double lon )
    {
        // we could use LinearKeyAlgo but this is simpler as we only need integer precision:
        return (down(lat) + 90) * 1000 + down(lon) + 180;
    }

    public void setDownloader( Downloader downloader )
    {
        this.downloader = downloader;
    }       

    public void setCacheDir( File cacheDir )
    {
        if (!cacheDir.isDirectory())
            throw new IllegalStateException("Cache path has to be a directory");

        if (!cacheDir.exists())
            cacheDir.mkdirs();
        this.cacheDir = cacheDir;
    }

    int down( double val )
    {
        int intVal = (int) val;
        if (val >= 0 || intVal - val < 1e-5)
            return intVal;
        return intVal - 1;
    }

    String getFileString( double lat, double lon )
    {
        int intKey = calcIntKey(lat, lon);
        String str = areas.get(intKey);
        if (str == null)
            throw new IllegalStateException("Area " + intKey + " not found for " + lat + "," + lon);

        int minLat = Math.abs(down(lat));
        int minLon = Math.abs(down(lon));
        str += "/";
        if (lat >= 0)
            str += "N";
        else
            str += "S";

        if (minLat < 10)
            str += "0";
        str += minLat;

        if (lon >= 0)
            str += "E";
        else
            str += "W";

        if (minLon < 10)
            str += "0";
        if (minLon < 100)
            str += "0";
        str += minLon;
        return str;
    }

    @Override
    public double getEle( double lat, double lon )
    {
        int intKey = calcIntKey(lat, lon);
        HeightTile demProvider = cacheData.get(intKey);
        if (demProvider == null)
        {
            String fileDetails = getFileString(lat, lon);
            String baseUrl = "http://dds.cr.usgs.gov/srtm/version2_1/SRTM3/";
            // mirror: base = "http://mirror.ufs.ac.za/datasets/SRTM3/"        
            String zippedURL = baseUrl + "/" + fileDetails + ".hgt.zip";
            File file = new File(cacheDir, new File(zippedURL).getName());
            int minLat = down(lat);
            int minLon = down(lon);
            demProvider = new HeightTile(minLat, minLon, WIDTH);
            cacheData.put(intKey, demProvider);
            try
            {
                InputStream is;
                if (!file.exists())
                {
                    // get zip file if not already in cacheDir - unzip later and in-memory only!
                    downloader.downloadFile(zippedURL, file.getAbsolutePath());
                }

                is = new FileInputStream(file);
                ZipInputStream zis = new ZipInputStream(is);
                zis.getNextEntry();
                BufferedInputStream buff = new BufferedInputStream(zis);
                byte[] bytes = new byte[2 * WIDTH * WIDTH];
                DataAccess heights = dir.find("dem" + intKey);
                heights.create(bytes.length);
                        
                demProvider.setHeights(heights);
                int len;
                while ((len = buff.read(bytes)) > 0)
                {
                    for (int bytePos = 0; bytePos < len; bytePos += 2)
                    {
                        short val = BIT_UTIL.toShort(bytes, bytePos);
                        if (val < -1000 || val > 10000)
                        {
                            // TODO fill unassigned gaps with neighbor values -> flood fill algorithm !
                            // -> calculate mean with an associated weight of how long the distance to the neighbor is
//                            throw new IllegalStateException("Invalid height value " + val
//                                    + ", y:" + bytePos / WIDTH + ", x:" + (WIDTH - bytePos % WIDTH));
                            val = Short.MIN_VALUE;
                        }

                        heights.setShort(bytePos, val);
                    }
                }
                // demProvider.toImage(file.getName() + ".png");
            } catch (Exception ex)
            {
                throw new RuntimeException(ex);
            }
        }

        // System.out.println(getter.getEle(52.882391, 4.63623));
        short val = demProvider.getHeight(lat, lon);
        if (val == Short.MIN_VALUE)
            return Double.NaN;
        return val;
    }
}