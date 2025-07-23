package com.infisense.usbir.thread;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.infisense.iruvc.sdkisp.LibIRParse;
import com.infisense.iruvc.sdkisp.LibIRProcess;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.SynchronizedBitmap;
import com.infisense.usbir.utils.PseudocolorModeTable;

import java.nio.ByteBuffer;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.24 11:06
 * @UpdateUser:
 * @UpdateDate:     2022.2.24 11:06
 * @UpdateRemark:
 */
public class ImageThread extends Thread {

    private String TAG = "ImageThread";
    private Context mContext;
    private Bitmap bitmap;
    private SynchronizedBitmap syncimage;
    private int imageWidth;
    private int imageHeight;
    private byte[] imageSrc;
    private byte[] temperatureSrc;
    private boolean rotate; // 屏幕旋转
    // 当前的等温尺状态
    private boolean biaochistatus = false;
    //
    private CommonParams.DataFlowMode dataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
    private byte[] imageYUV422;
    private byte[] imageARGB;
    private byte[] imageDst;
    private byte[] imageY8;

    /**
     * @param syncimage
     */
    public void setSyncimage(SynchronizedBitmap syncimage) {
        this.syncimage = syncimage;
    }

    /**
     * @param imageSrc
     */
    public void setImageSrc(byte[] imageSrc) {
        this.imageSrc = imageSrc;
    }

    /**
     * @param temperatureSrc
     */
    public void setTemperatureSrc(byte[] temperatureSrc) {
        this.temperatureSrc = temperatureSrc;
    }

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * @param context
     * @param imageWidth
     * @param imageHeight
     */
    public ImageThread(Context context, int imageWidth, int imageHeight) {
        Log.i(TAG, "ImageThread create->imageWidth = " + imageWidth + " imageHeight = " + imageHeight);
        this.mContext = context;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        imageYUV422 = new byte[imageWidth * imageHeight * 2];
        imageARGB = new byte[imageWidth * imageHeight * 4];
        imageDst = new byte[imageWidth * imageHeight * 4];
        imageY8 = new byte[imageWidth * imageHeight];
    }

    /**
     * @param dataFlowMode
     */
    public void setDataFlowMode(CommonParams.DataFlowMode dataFlowMode) {
        this.dataFlowMode = dataFlowMode;
    }

    /**
     * @param bitmap
     */
    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            synchronized (syncimage.dataLock) {
                if (syncimage.start) {
                    Log.i(TAG, "IRUVC_DATA run->dataFlowMode = " + dataFlowMode + " rotate = " + rotate +
                            " syncimage.valid = " + syncimage.valid +
                            " imageSrc.length = " + imageSrc.length +
                            " imageSrc[100] = " + imageSrc[100]);
                    if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT ||
                            dataFlowMode == CommonParams.DataFlowMode.IMAGE_OUTPUT) {
                        // yuv422格式
                        /**
                         * 方式1：
                         * 使用系统伪彩,通过setPseudoColor接口设置伪彩
                         */
                        LibIRParse.converyArrayYuv422ToARGB(imageSrc, imageHeight * imageWidth, imageARGB);

                        /**
                         * 方式2：
                         * 使用本地存储的自定义伪彩，传入伪彩表数据
                         */
//                        // YUV伪彩
//                        String path = FileUtil.getSaveFilePath(mContext);
//                        String pseudoYUVDataIntStr = FileUtil.getStringFromFile(path + PopupOthers
//                                .COLOR_YUV_DATA_INT);
//                        Log.d(TAG, "pseudoYUVDataIntStr : " + pseudoYUVDataIntStr);
//                        if (pseudoYUVDataIntStr != null && !pseudoYUVDataIntStr.isEmpty()) {
//                            int[][] pseudoYUVDataInt = JSON.parseObject(pseudoYUVDataIntStr, int[][].class);
//                            LibIRProcess.convertYuyvMapToARGBCustomPseudocolor(imageSrc, (long) imageHeight *
//                                    imageWidth, pseudoYUVDataInt, imageARGB);
//                        }
                        /**
                         * 方式3：
                         * 使用assets中的自定义伪彩，传入伪彩表数据
                         */
//                        byte[] pseudoDataByte = new byte[768]; // 伪彩数据,长度固定
//                        AssetManager am = mContext.getAssets();
//                        InputStream is;
//                        try {
//                            is = am.open("pseudocolor/Ironbow.bin");
//                            int lenth = is.available();
//                            pseudoDataByte = new byte[lenth];
//                            if (is.read(pseudoDataByte) != lenth) {
//                                Log.e(TAG, "read file fail ");
//                            }
//                            //
//                            is.close();
//                        } catch (IOException e) {
//                            e.printStackTrace();
//                        }
//                        // 格式转换 YUV伪彩
//                        int[][] pseudoYUVDataInt2 = CommonUtils.convertYUVPseudocolorData(pseudoDataByte);
//                        LibIRProcess.convertYuyvMapToARGBCustomPseudocolor(imageSrc, (long) imageHeight *
//                                imageWidth, pseudoYUVDataInt2, imageARGB);

                        /**
                         * 方式4：
                         * 调用SDK中实现的伪彩
                         */
//                        LibIRProcess.convertYuyvMapToARGBPseudocolor(imageSrc, (long) imageHeight * imageWidth,
//                                CommonParams.PseudoColorType.PSEUDO_3, imageARGB);
                    } else {
                        // 调用 startY16ModePreview 中间出图方法之后，输出的数据格式为y16,需要做转换
                        /**
                         * 方式1：YUV伪彩
                         */
                        LibIRParse.convertArrayY14ToYuv422(imageSrc, imageHeight * imageWidth, imageYUV422);
                        LibIRParse.converyArrayYuv422ToARGB(imageYUV422, imageHeight * imageWidth, imageARGB);
                        /**
                         *  方式2：RGB伪彩
                         */
//                        LibIRParse.convertArrayY14ToY8(imageSrc, imageHeight * imageWidth, imageY8);
//                        LibIRProcess.convertGrayMapToARGBPseudocolorM2(imageY8, (long) imageHeight * imageWidth,
//                        CommonParams.PseudoColorTypeM2.IRPROC_COLOR_YP0103, imageARGB);
                    }

                    /**
                     * 等温尺处理,展示伪彩的温度范围内信息
                     */
                    if (biaochistatus && temperatureSrc != null) {
                        //for biaochi filter
                        int j = 0;
                        int imageDstLength = imageWidth * imageHeight * 4;
                        float biaochiMax = 40, biaochiMin = 25; // 温度阈值设定
                        // 遍历像素点，过滤温度阈值
                        for (int index = 0; index < imageDstLength; ) {
                            // 温度换算公式
                            float temperature0 = (temperatureSrc[j] & 0xff) + (temperatureSrc[j + 1] & 0xff) * 256;
                            temperature0 = (float) (temperature0 / 64 - 273.15);
                            // 处理温度范围之外的像素点
                            /**
                             * 方式1：温度阈值范围之外的处理成白热
                             */
//                            int y0 = imageSrc[j] & 0xff;
//                            if ((temperature0 < biaochiMin) || (temperature0 > biaochiMax)) {
//                                imageARGB[index] = (byte) PseudocolorModeTable.pseudocolorMapTableOfBAIRE[y0][0];
//                                imageARGB[index + 1] = (byte) PseudocolorModeTable.pseudocolorMapTableOfBAIRE[y0][1];
//                                imageARGB[index + 2] = (byte) PseudocolorModeTable.pseudocolorMapTableOfBAIRE[y0][2];
//                            }
                            /**
                             * 方式2：温度阈值范围之外的处理成固定的颜色
                             */
                            if (temperature0 < biaochiMin) {
                                imageARGB[index] = (byte) PseudocolorModeTable.BLUE_RGB[0];
                                imageARGB[index + 1] = (byte) PseudocolorModeTable.BLUE_RGB[1];
                                imageARGB[index + 2] = (byte) PseudocolorModeTable.BLUE_RGB[2];
                            } else if (temperature0 > biaochiMax) {
                                imageARGB[index] = (byte) PseudocolorModeTable.RED_RGB[0];
                                imageARGB[index + 1] = (byte) PseudocolorModeTable.RED_RGB[1];
                                imageARGB[index + 2] = (byte) PseudocolorModeTable.RED_RGB[2];
                            }
                            imageARGB[index + 3] = (byte) 255;
                            index += 4;
                            j += 2;
                        }
                    }

                    /**
                     * 经过转换之后的红外数据
                     * 其中的数据是旋转90度的，需要旋转回来,红外旋转的逻辑放在这里处理。
                     */
                    if (rotate) {
                        LibIRProcess.ImageRes_t imageRes = new LibIRProcess.ImageRes_t();
                        imageRes.height = (char) imageWidth;
                        imageRes.width = (char) imageHeight;
                        LibIRProcess.rotateRight90(imageARGB, imageRes,
                                CommonParams.IRPROCSRCFMTType.IRPROC_SRC_FMT_ARGB8888, imageDst);
                    } else {
                        imageDst = imageARGB;
                    }
                }
            }

            synchronized (syncimage.viewLock) {
                if (!syncimage.valid) {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageDst));
                    syncimage.valid = true;
                    syncimage.viewLock.notify();
                }
            }
            SystemClock.sleep(20);
        }
        Log.i(TAG, "ImageThread exit");
    }

}