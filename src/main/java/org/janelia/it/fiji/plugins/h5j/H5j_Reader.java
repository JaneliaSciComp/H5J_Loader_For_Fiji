/*
 * Copyright 2018 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */
package org.janelia.it.fiji.plugins.h5j;

import ij.*;
import ij.io.*;
import ij.process.*;
import ij.plugin.*;
import ij.process.ImageProcessor;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.awt.Color;

import org.apache.commons.io.FileUtils;

import ch.systemsx.cisd.hdf5.*;

public class H5j_Reader extends ImagePlus implements PlugIn {
    
    private static final String MESSAGE_PREFIX = "HHMI_H5J_Reader: ";
    private static final String EXTENSION = ".h5j";
    public static final String INFO_PROPERTY = "Info";

    private static final String VX_SIZE_ATTRIB = "voxel_size";
	private static final String UNIT_ATTRIB = "unit";
    private static final String PAD_RIGHT_ATTRIB = "pad_right";
    private static final String PAD_BOTTOM_ATTRIB = "pad_bottom";
    private static final String PAD_WIDTH_ATTRIB = "width";
    private static final String PAD_HEIGHT_ATTRIB = "height";
    private static final String CHANNELS_QUERY_PATH = "/Channels";

	private String _filename;
	private String _directory;
    private IHDF5Reader _reader;

    boolean isUnix = true;

    @Override
    public void run(String arg) {
    	String path = null;  
    	
    	if (arg != null) {  
            if (arg.indexOf("http://") == 0 || new File(arg).exists())
            	path = arg;  
    	} else {
    		// else, ask:  
    		OpenDialog od = new OpenDialog("Choose a .h5j file", null);  
    		String dir = od.getDirectory();
    		if (dir != null) {
    			if (!dir.endsWith(File.separator)) dir += File.separator;  
    			path = dir + od.getFileName();
    		}
    	}
        
        if (path == null) return;  
        File testf = new File(path);
    	_directory = testf.getParentFile().getAbsolutePath() + File.separator;
        _filename = testf.getName();
        if (_filename == null)
			return;

		String options = Macro.getOptions();
		int threads = 0;
		if (options != null) {
			String [] arguments = options.split(" ");
			for (String s : arguments) {
				String [] p = s.split("=");
				if (p == null) continue;
				if (p[0].equals("threads") && p.length >= 2) {
					threads = Integer.parseInt(p[1]);
				}
			}
		}
		//IJ.log("threads: "+threads);

		String os = System.getProperty("os.name");
		if (os.contains("Windows")) 
			isUnix = false;

    	readStackHDF5(_directory, _filename, threads);
    	
    	if (arg == null || arg.trim().length() == 0) this.show();
    }

	boolean readStackHDF5(String directory, String filename, int threadnum)
    {
        try {

        	IHDF5ReaderConfigurator conf = HDF5Factory.configureForReading(directory+filename);
        	conf.performNumericConversions();
        	_reader = conf.reader();

        	int width = -1;
        	if (_reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_WIDTH_ATTRIB)) {
            	IHDF5LongReader ihdf5LongReader = _reader.int64();
            	width = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_WIDTH_ATTRIB);
        	}
        	
        	int height = -1;
        	if (_reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_HEIGHT_ATTRIB)) {
            	IHDF5LongReader ihdf5LongReader = _reader.int64();
            	height = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_HEIGHT_ATTRIB);
        	}
			
			int paddingBottom = -1;
        	if (_reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_BOTTOM_ATTRIB)) {
            	IHDF5LongReader ihdf5LongReader = _reader.int64();
            	paddingBottom = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_BOTTOM_ATTRIB);
        	}
        	
        	int paddingRight = -1;
        	if (_reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_RIGHT_ATTRIB)) {
            	IHDF5LongReader ihdf5LongReader = _reader.int64();
            	paddingRight = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_RIGHT_ATTRIB);
        	}
        
        	double[] vxsize = null;
        	if (_reader.object().hasAttribute("/", VX_SIZE_ATTRIB)) {
            	IHDF5DoubleReader ihdf5DoubleReader = _reader.float64();
            	vxsize = ihdf5DoubleReader.getArrayAttr("/", VX_SIZE_ATTRIB);
        	}

        	String unit = null;
        	if (_reader.object().hasAttribute("/", UNIT_ATTRIB)) {
            	IHDF5StringReader ihdf5StringReader = _reader.string();
            	unit = ihdf5StringReader.getAttr("/", UNIT_ATTRIB);
        	}

        	int nCh = _reader.object().getAllGroupMembers(CHANNELS_QUERY_PATH).size();

        	Path myTempDir = Files.createTempDirectory(null);
            String tdir = myTempDir.toString() + "/";
            String imageseq_path = tdir + "z%05d.tiff";

            ArrayList<ImageProcessor> slices = new ArrayList<ImageProcessor>();
            int slicenum = 0;
            int bdepth = 8;
            for ( int c = 0; c < nCh; c++ )
            {
            	IJ.showProgress((double)c / nCh);
            	IHDF5OpaqueReader channel = _reader.opaque();
        		byte[] data = channel.readArray(CHANNELS_QUERY_PATH + "/" + "Channel_"+c);
				String vpath = tdir + "v.mp4";
        		BufferedOutputStream ost = new BufferedOutputStream(new FileOutputStream(vpath));
        		ost.write(data);
        		ost.close();

        		//get bit depth
        		if (c == 0) { 
            		String[] listCommands = {
						IJ.getDirectory("plugins")+"/ffprobe",
						"-v", "error",
						"-select_streams", "v",
						"-of", "default=noprint_wrappers=1:nokey=1",
						"-show_entries", "stream=pix_fmt",
						vpath
					};
					FFMPEGThread ffprobe = new FFMPEGThread(listCommands);
        			ffprobe.start();
        			ffprobe.join();
        			if (ffprobe.getStdOut().contains("12le") || ffprobe.getStdOut().contains("12be"))
					bdepth = 16;

					//IJ.log(ffprobe.getStdOut());
        		}

				String[] listCommands = {
					IJ.getDirectory("plugins")+"/ffmpeg",
					"-y",
					"-i", vpath,
					"-compression_algo", "raw",
					"-pix_fmt", bdepth==8 ? "gray8" : "gray16",
					imageseq_path
				};
				FFMPEGThread ffmpeg = new FFMPEGThread(listCommands);
        		ffmpeg.start();
        		ffmpeg.join();

				int inum = 1;
				String path = tdir + String.format("z%05d", inum) + ".tiff";
				File tif = new File(path);
				BufferedInputStream ist = null;
				//IJ.log(path);
				while (tif.exists() && !tif.isDirectory()) {
					TiffDecoder tfd = new TiffDecoder(tdir, String.format("z%05d", inum) + ".tiff");
					if (tfd == null) break;
					FileInfo[] fi_list = tfd.getTiffInfo();
					if (fi_list == null) break;

					byte [] impxs = new byte[(width+paddingRight)*(height+paddingBottom)*(bdepth/8)];
					ist = new BufferedInputStream(new FileInputStream(path));
					ist.skip(fi_list[0].getOffset());
					ist.read(impxs);

					ImageProcessor ip = null;
					if (bdepth == 8)
						ip = (ImageProcessor)( new ByteProcessor(fi_list[0].width, fi_list[0].height, impxs) );
					else {
						short [] impxs_s = new short[(width+paddingRight)*(height+paddingBottom)];
						ByteBuffer.wrap(impxs).order(fi_list[0].intelByteOrder?ByteOrder.LITTLE_ENDIAN:ByteOrder.BIG_ENDIAN).asShortBuffer().get(impxs_s);
						ip = (ImageProcessor)( new ShortProcessor(fi_list[0].width, fi_list[0].height, impxs_s, null) );
					}
					
					if (paddingBottom > 0 || paddingRight > 0) {
						ip.setRoi(0, 0, width, height);
						slices.add(ip.crop());
					} else
						slices.add(ip);
					path = tdir + String.format("z%05d", ++inum) + ".tiff";
					tif = new File(path);
				}
				
				if (slicenum <= 0) slicenum = inum-1;
				else if (slicenum != inum-1) break;

				//IJ.log("slicenum: "+slicenum);
        		
        		//IJ.log(ffmpeg.getStdErr());
            }
            IJ.showProgress(1.0);

            ImageStack finalstack = new ImageStack(width, height);
            for (int z = 0; z < slicenum; z++) {
            	for (int c = 0; c < nCh; c++) 
            		finalstack.addSlice(slices.get(c*slicenum+z));
            }
            this.setStack(filename, finalstack);
            if (bdepth == 16) IJ.run(this, "Divide...", "value=16 stack");
            if (nCh > 2) {
            	this.setDimensions(nCh, slicenum, 1);
            	this.setOpenAsHyperStack(true);
            	
            	/*
				LUT[] luts = new LUT[nCh];
				luts[0] = LUT.createLutFromColor(new Color(255,0,0));
				luts[1] = LUT.createLutFromColor(new Color(0,255,0));
				if (nCh >= 3) luts[2] = LUT.createLutFromColor(new Color(0,0,255));
				if (nCh >= 4) {
					for (int i = 3; i < nCh; i++)
						luts[i] = LUT.createLutFromColor(new Color(255,255,255));
				}
				*/
            	
				double range = (bdepth==8 ? 255.0 : 4095.0); 
				this.setDisplayMode(IJ.COMPOSITE);
				this.setC(1);
				//this.setLut(luts[0]);
				this.setDisplayRange(0.0, range);
				this.setC(2);
				//this.setLut(luts[1]);
				this.setDisplayRange(0.0, range);
				this.setC(3);
				//this.setLut(luts[2]);
				this.setDisplayRange(0.0, range);

				this.setC(1);
            } else {
            	double range = (bdepth==8 ? 255.0 : 4095.0); 
            	this.setDisplayRange(0.0, range);
            }

            FileUtils.deleteDirectory(new File(myTempDir.toString()));
            
            return true;
        }catch(Exception e){
        	e.printStackTrace();
        }

        return false;
	}

    class FFMPEGThread extends Thread{
        String[] command;
        StringBuffer stdout_sb = new StringBuffer();
        StringBuffer stderr_sb = new StringBuffer();
        FFMPEGThread(String[] command){        
            this.command=command;            
        }
        public void run(){      
			try{          
				String s = null;
                Process process = new ProcessBuilder(command).start();                
                BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                // read the output from the command                    
                while ((s = stdInput.readLine()) != null)
                    stdout_sb.append(s+"\n");
                stdInput.close();
                // read any errors from the attempted command                
                while ((s = stdError.readLine()) != null)
                    stderr_sb.append(s+"\n");    
            }catch(Exception ex){
                System.out.println(ex.toString());
            }
        }
        public String getStdOut() { return stdout_sb.toString(); }
        public String getStdErr() { return stderr_sb.toString(); }
    }
}
