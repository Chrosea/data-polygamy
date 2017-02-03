/* Copyright (C) 2016 New York University
   This file is part of Data Polygamy which is released under the Revised BSD License
   See file LICENSE for full license details. */
package edu.nyu.vida.data_polygamy.scalar_function_computation;

import java.io.IOException;
import java.util.HashMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import edu.nyu.vida.data_polygamy.resolution.SpatialResolution;
import edu.nyu.vida.data_polygamy.resolution.SpatialResolutionUtils;
import edu.nyu.vida.data_polygamy.resolution.ToCity;
import edu.nyu.vida.data_polygamy.utils.FrameworkUtils;
import edu.nyu.vida.data_polygamy.utils.FrameworkUtils.AggregationArrayWritable;
import edu.nyu.vida.data_polygamy.utils.FrameworkUtils.MultipleSpatioTemporalWritable;
import edu.nyu.vida.data_polygamy.utils.FrameworkUtils.SpatioTemporalWritable;

public class AggregationMapper extends Mapper<MultipleSpatioTemporalWritable, AggregationArrayWritable, SpatioTemporalWritable, AggregationArrayWritable> {
    
    public static FrameworkUtils utils = new FrameworkUtils();
    
    HashMap<String,String> datasetToId = new HashMap<String,String>();
    
    int spatialIndex, tempIndex;
    int currentTemporal, currentSpatial;
    int[] temporalResolutions, spatialResolutions;
    int invalidSpatial = -1;
    int invalidTemporal = -1;
    int datasetId = -1;
    
    int temporalResolution, spatialResolution;
    boolean sameResolution = false;
    SpatialResolution spatialTranslation;
    
    // output key
    SpatioTemporalWritable keyWritable = new SpatioTemporalWritable();
    
    private SpatialResolution resolveResolution(int currentSpatialResolution,
            int spatialPos, Configuration conf) {
        
        SpatialResolution spatialTranslation = null;
        int[] spatialPosArray = new int[1];
        spatialPosArray[0] = spatialPos;
        
        switch (currentSpatialResolution) {
        
        case FrameworkUtils.NBHD:
            spatialTranslation = SpatialResolutionUtils.nbhdResolution(spatialResolution,
            		spatialPosArray);
            break;
        case FrameworkUtils.ZIP:
            spatialTranslation = SpatialResolutionUtils.zipResolution(spatialResolution,
            		spatialPosArray, false, conf);
            break;
        case FrameworkUtils.GRID:
            spatialTranslation = SpatialResolutionUtils.gridResolution(spatialResolution,
            		spatialPosArray);
            break;
        case FrameworkUtils.BLOCK:
            spatialTranslation = SpatialResolutionUtils.blockResolution(spatialResolution,
                    spatialPosArray, false, conf);
            break;
        case FrameworkUtils.CITY:
            spatialTranslation = new ToCity(spatialPosArray);
            break;
        default:
            System.out.println("Something is wrong...");
            System.exit(-1);
        }
        
        return spatialTranslation;
    }

    @Override
    public void setup(Context context)
            throws IOException, InterruptedException {
    
        Configuration conf = context.getConfiguration();
        
        String[] datasetNames = conf.get("dataset-name","").split(",");
        String[] datasetIds = conf.get("dataset-id","").split(",");
        for (int i = 0; i < datasetNames.length; i++)
            datasetToId.put(datasetNames[i], datasetIds[i]);
        
        FileSplit fileSplit = (FileSplit) context.getInputSplit();
        String[] fileSplitTokens = fileSplit.getPath().getParent().toString().split("/");
        String[] filenameTokens = fileSplitTokens[fileSplitTokens.length-1].split("-");
        String dataset = "";
        for (int i = 0; i < filenameTokens.length-2; i++) {
            dataset += filenameTokens[i] + "-";
        }
        dataset = dataset.substring(0, dataset.length()-1);
        String datasetIdStr = datasetToId.get(dataset);
        
        currentTemporal = utils.temporalResolution(conf.get("dataset-" + datasetIdStr +
                "-temporal",""));
        currentSpatial = utils.spatialResolution(conf.get("dataset-" + datasetIdStr +
                "-spatial",""));
        
        datasetId = Integer.parseInt(datasetIdStr);
        
        try {
            tempIndex = Integer.parseInt(conf.get("dataset-" + datasetIdStr +
                    "-temporal-att","0"));
            spatialIndex = Integer.parseInt(conf.get("dataset-" + datasetIdStr +
                    "-spatial-att", "0"));
        } catch (NumberFormatException e) {
            e.printStackTrace();
            System.exit(-1);
        }
        
        String[] temporalResolutionArray = conf.get("dataset-" + datasetIdStr +
                "-temporal-resolutions","").split("-");
        String[] spatialResolutionArray = conf.get("dataset-" + datasetIdStr +
                "-spatial-resolutions","").split("-");
        
        temporalResolutions = new int[temporalResolutionArray.length];
        spatialResolutions = new int[spatialResolutionArray.length];
        
        for (int i = 0; i < temporalResolutionArray.length; i++) {
        	temporalResolutions[i] = utils.temporalResolution(temporalResolutionArray[i]);
        }
        for (int i = 0; i < spatialResolutionArray.length; i++) {
        	spatialResolutions[i] = utils.spatialResolution(spatialResolutionArray[i]);
        }
        
        /**
         * Spatial Translations
         */
        
        spatialTranslation = resolveResolution(currentSpatial, spatialIndex,
                context.getConfiguration());
    }
    
    @Override
    public void map(MultipleSpatioTemporalWritable key, AggregationArrayWritable value, Context context)
            throws IOException, InterruptedException {
        
        for (int i = 0; i < temporalResolutions.length; i++) {
            
            // input
            int[] spatialArray = key.getSpatial();
            int[] temporalArray = key.getTemporal();
            
            int spatialAtt = -1;
            int temporalAtt = -1;
            
    		temporalResolution = temporalResolutions[i];
    		spatialResolution = spatialResolutions[i];
    		
    		sameResolution = (temporalResolution == currentTemporal) &&
    				(spatialResolution == currentSpatial);
    		
    		if (sameResolution) {
                spatialAtt = spatialArray[spatialIndex];
                temporalAtt = temporalArray[tempIndex];
                if ((spatialAtt != invalidSpatial) && (temporalAtt != invalidTemporal)) {
                    keyWritable = new SpatioTemporalWritable(spatialAtt,
                            temporalAtt, spatialResolution, temporalResolution,
                            datasetId);
                    context.write(keyWritable, value);
                }
                continue;
            }
            
            /**
             * Spatial Resolution
             */
            
            spatialAtt = spatialArray[spatialIndex];
            if (spatialAtt == invalidSpatial)
                continue;
            
            if (currentSpatial != spatialResolution)
                spatialAtt = spatialTranslation.translate(spatialArray);
            
            /**
             * Temporal Resolution
             */
            
            if (currentTemporal == temporalResolution)
                temporalAtt = temporalArray[tempIndex];
            else
                temporalAtt = FrameworkUtils.getTime(temporalResolution, temporalArray, tempIndex);
            
            if (temporalAtt == -1)
                continue;
            
            keyWritable = new SpatioTemporalWritable(spatialAtt, temporalAtt,
            		spatialResolution, temporalResolution, datasetId);
            context.write(keyWritable, value);
        }
    }
    
}
