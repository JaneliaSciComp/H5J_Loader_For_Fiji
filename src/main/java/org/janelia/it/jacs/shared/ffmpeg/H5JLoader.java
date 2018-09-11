/*
 * Copyright 2015 Howard Hughes Medical Institute.
 * All rights reserved.
 * Use is subject to Janelia Farm Research Campus Software Copyright 1.1
 * license terms ( http://license.janelia.org/license/jfrc_copyright_1_1.html ).
 */

package org.janelia.it.jacs.shared.ffmpeg;

import ch.systemsx.cisd.hdf5.*;

import java.util.List;
import java.util.ListIterator;

public class H5JLoader
{
	private static final String SPC_X_ATTRIB = "spcx";
	private static final String SPC_Y_ATTRIB = "spcy";
	private static final String SPC_Z_ATTRIB = "spcz";
	private static final String UNIT_ATTRIB = "unit";
    private static final String PAD_RIGHT_ATTRIB = "pad_right";
    private static final String PAD_BOTTOM_ATTRIB = "pad_bottom";
    private static final String CHANNELS_QUERY_PATH = "/Channels";

    private String _filename;
    private IHDF5Reader _reader;
    private ImageStack _image;
    
    public H5JLoader(String filename) {
        this._filename = filename;
        IHDF5ReaderConfigurator conf = HDF5Factory.configureForReading(filename);
        conf.performNumericConversions();
        _reader = conf.reader();
    }

    public void close() throws Exception {
        _reader.close();
    }

    public int numberOfChannels() {
        return _reader.object().getAllGroupMembers(CHANNELS_QUERY_PATH).size();
    }

    public List<String> channelNames() { return _reader.object().getAllGroupMembers(CHANNELS_QUERY_PATH); }

    public ImageStack extractAllChannels() {
        if (_image == null) {
            _image = new ImageStack();
        }

        List<String> channels = channelNames();
        for (ListIterator<String> iter = channels.listIterator(); iter.hasNext(); )
        {
            String channel_id = iter.next();
            try
            {
                ImageStack frames = extract(channel_id);
                _image.merge( frames );
                extractAttributes(_image);
            } catch (Exception e)
            {
                e.printStackTrace();
            }
        }

        return _image;
    }

    public ImageStack extract(String channelID) throws Exception {
        IHDF5OpaqueReader channel = _reader.opaque();
        byte[] data = channel.readArray(CHANNELS_QUERY_PATH + "/" + channelID);

        FFMpegLoader movie = new FFMpegLoader(data);
        movie.start();
        movie.grab();
        ImageStack stack = movie.getImage();

        extractAttributes(stack);

        movie.close();

        return stack;
    }

    private void extractAttributes(ImageStack image) {
        if (image == null) {
            image = new ImageStack();
        }
        IHDF5ReaderConfigurator conf = HDF5Factory.configureForReading(_filename);
        conf.performNumericConversions();
        IHDF5Reader ihdf5reader = conf.reader();
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_BOTTOM_ATTRIB)) {
            IHDF5LongReader ihdf5LongReader = ihdf5reader.int64();
            final int paddingBottom = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_BOTTOM_ATTRIB);
            image.setPaddingBottom(paddingBottom);
        } else {
            image.setPaddingBottom(-1);
        }
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, PAD_RIGHT_ATTRIB)) {
            IHDF5LongReader ihdf5LongReader = ihdf5reader.int64();
            final int paddingRight = (int) ihdf5LongReader.getAttr(CHANNELS_QUERY_PATH, PAD_RIGHT_ATTRIB);
            image.setPaddingRight(paddingRight);
        } else {
            image.setPaddingRight(-1);
        }
        
        double spcx = 1.0;
        double spcy = 1.0;
        double spcz = 1.0;
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, SPC_X_ATTRIB)) {
            IHDF5DoubleReader ihdf5DoubleReader = ihdf5reader.float64();
            spcx = ihdf5DoubleReader.getAttr(CHANNELS_QUERY_PATH, SPC_X_ATTRIB);
        }
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, SPC_Y_ATTRIB)) {
            IHDF5DoubleReader ihdf5DoubleReader = ihdf5reader.float64();
            spcy = ihdf5DoubleReader.getAttr(CHANNELS_QUERY_PATH, SPC_Y_ATTRIB);
        }
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, SPC_Z_ATTRIB)) {
            IHDF5DoubleReader ihdf5DoubleReader = ihdf5reader.float64();
            spcz = ihdf5DoubleReader.getAttr(CHANNELS_QUERY_PATH, SPC_Z_ATTRIB);
        }
        image.setSpacings(spcx, spcy, spcz);
        
        if (ihdf5reader.object().hasAttribute(CHANNELS_QUERY_PATH, UNIT_ATTRIB)) {
            IHDF5StringReader ihdf5StringReader = ihdf5reader.string();
            final String unit = ihdf5StringReader.getAttr(CHANNELS_QUERY_PATH, UNIT_ATTRIB);
            image.setUnit(unit);
        }
    }


    public void saveFrame(int iFrame, DataAcceptor acceptor)
            throws Exception {
        int width = _image.width();
        int height = _image.height();
        byte[] data = _image.interleave(iFrame, 0, 3);
        int linesize = _image.linesize(iFrame);
        acceptor.accept(data, linesize, width, height);
    }
    
    public static interface DataAcceptor {
        void accept(byte[] data, int linesize, int width, int height);
    }

}