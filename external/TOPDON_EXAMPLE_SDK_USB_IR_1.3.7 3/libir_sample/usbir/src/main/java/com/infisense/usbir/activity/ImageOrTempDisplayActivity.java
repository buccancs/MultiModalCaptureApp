package com.infisense.usbir.activity;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.infisense.iruvc.ircmd.IRCMD;
import com.infisense.iruvc.ircmd.ResultCode;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.SynchronizedBitmap;
import com.infisense.iruvc.uvc.ConnectCallback;
import com.infisense.iruvc.uvc.UVCCamera;
import com.infisense.usbir.R;
import com.infisense.usbir.camera.IRUVC;
import com.infisense.usbir.databinding.ActivityImageOrTempDisplayBinding;
import com.infisense.usbir.thread.ImageThread;
import com.infisense.usbir.utils.ScreenUtils;
import com.infisense.usbir.utils.USBMonitorCallback;


/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 16:47
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 16:47
 * @UpdateRemark:
 */
public class ImageOrTempDisplayActivity extends BaseActivity implements View.OnClickListener,
        AdapterView.OnItemSelectedListener {

    private static final String TAG = "ImageOrTempDisplayActivity";
    private ActivityImageOrTempDisplayBinding binding;
    // 中间出图选项
    private static final String[] y16ModePreviewSrcType = {"Y16_MODE_TEMPERATURE", "Y16_MODE_IR", "Y16_MODE_KBC",
            "Y16_MODE_HBC_DPC", "Y16_MODE_VBC", "Y16_MODE_TNR", "Y16_MODE_SNR", "Y16_MODE_AGC", "Y16_MODE_DDE",
            "Y16_MODE_GAMMA", "Y16_MODE_MIRROR"};

    private ImageThread imageThread;
    private Bitmap bitmap;
    private IRUVC iruvc;
    private IRCMD ircmd;
    private UVCCamera uvcCamera;
    private CommonParams.DataFlowMode defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
    // 是否使用IRISP算法集成
    private boolean isUseIRISP = false;
    // 是否使用GPU方案
    private boolean isUseGPU = false;

    private int cameraWidth; // 传感器的原始宽度
    private int cameraHeight;// 传感器的原始高度
    private int tempHeight; // 温度数据高度
    private int imageWidth; // 经过旋转后的图像宽度
    private int imageHeight; // 经过旋转后的图像高度

    private byte[] imageSrc;
    private byte[] temperatureSrc;
    private SynchronizedBitmap syncimage = new SynchronizedBitmap();
    private boolean isrun = false;
    private RelativeLayout.LayoutParams fullScreenlayoutParams;
    // progressDialog
    private AlertDialog progressDialog;

    //
    public static final int RESTART_USB = 1000;
    public static final int START_PREVIEW_COMPLETE = 1001;
    public static final int IRCMD_INIT_FAIL = 1002;

    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == RESTART_USB) {
                restartusbcamera();
            } else if (msg.what == IRCMD_INIT_FAIL) {
                ResultCode resultCode = (ResultCode) msg.obj;
                ScreenUtils.showNormalDialog(ImageOrTempDisplayActivity.this,
                        "error code : " + resultCode.getCode() + "\n"
                                + "error message :" + resultCode.getMsg(), new PopupWindow.OnDismissListener() {
                            @Override
                            public void onDismiss() {
                                finish();
                            }
                        });
            } else if (msg.what == START_PREVIEW_COMPLETE) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                if (iruvc != null) {
                    iruvc.setFrameReady(true);
                }
            }
        }
    };

    @Override
    protected View getContentView() {
        binding = ActivityImageOrTempDisplayBinding.inflate(getLayoutInflater());
        return binding.getRoot();
    }

    @Override
    public void initView() {
        binding.manualShutButton.setOnClickListener(this);
        binding.btnImageTemp.setOnClickListener(this);
        binding.btnImage.setOnClickListener(this);
        binding.btnY16ModeSet.setOnClickListener(this);
    }

    @Override
    protected void init(Bundle savedInstanceState) {
        initDataFlowMode(defaultDataFlowMode);
        initdata();
        //
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.spinner_custom, y16ModePreviewSrcType);
        binding.ParamY16ModeType.setAdapter(adapter);
        binding.ParamY16ModeType.setOnItemSelectedListener(this);
        binding.ParamY16ModeType.setSelection(0);
    }

    /**
     * @param dataFlowMode
     */
    private void initDataFlowMode(CommonParams.DataFlowMode dataFlowMode) {
        if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT) {
            /**
             * 图像+温度 YUV422数据
             */
            cameraWidth = 256; // 传感器的原始宽度
            cameraHeight = 384; // 传感器的原始高度
            tempHeight = 192;
            //
            binding.btnImageTemp.setTextColor(ContextCompat.getColor(this, R.color.red));
            binding.btnImage.setTextColor(ContextCompat.getColor(this, R.color.black));
            binding.btnY16ModeSet.setTextColor(ContextCompat.getColor(this, R.color.black));
        } else if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_OUTPUT) {
            /**
             * 图像 YUV422数据
             */
            cameraWidth = 256;// 传感器的原始宽度
            cameraHeight = 192;// 传感器的原始高度
            tempHeight = 0;
            //
            binding.btnImageTemp.setTextColor(ContextCompat.getColor(this, R.color.black));
            binding.btnImage.setTextColor(ContextCompat.getColor(this, R.color.red));
            binding.btnY16ModeSet.setTextColor(ContextCompat.getColor(this, R.color.black));
        } else {
            /**
             * 中间出图 y16数据
             */
            cameraWidth = 256;// 传感器的原始宽度
            cameraHeight = 192;// 传感器的原始高度
            tempHeight = 0;
            //
            binding.btnImageTemp.setTextColor(ContextCompat.getColor(this, R.color.black));
            binding.btnImage.setTextColor(ContextCompat.getColor(this, R.color.black));
            binding.btnY16ModeSet.setTextColor(ContextCompat.getColor(this, R.color.red));
        }
        imageWidth = cameraHeight - tempHeight;
        imageHeight = cameraWidth;

        imageSrc = new byte[imageWidth * imageHeight * 2];
        temperatureSrc = new byte[imageWidth * imageHeight * 2];
    }

    /**
     *
     */
    private void initdata() {
        // 计算画面的宽高，避免被拉伸变形
        int screenWidth = ScreenUtils.getScreenWidth(this);
        fullScreenlayoutParams = new RelativeLayout.LayoutParams(screenWidth,
                imageHeight * screenWidth / imageWidth);
        fullScreenlayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        //
        bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        binding.cameraView.setSyncimage(syncimage);
        binding.cameraView.setBitmap(bitmap);
        binding.cameraView.setLayoutParams(fullScreenlayoutParams);
    }

    /**
     *
     */
    private void startISP() {
        imageThread = new ImageThread(ImageOrTempDisplayActivity.this, imageWidth, imageHeight);
        imageThread.setDataFlowMode(defaultDataFlowMode);
        imageThread.setSyncimage(syncimage);
        imageThread.setImageSrc(imageSrc);
        imageThread.setBitmap(bitmap);
        imageThread.setRotate(true);
        imageThread.start();
    }

    /**
     * 初始化ProgressDialog
     */
    private void initProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(R.layout.layout_loading);
        builder.setCancelable(true);
        progressDialog = builder.create();
    }

    /**
     *
     */
    private void startUSB(boolean isReStart) {
        if (progressDialog == null) {
            initProgressDialog();
        }
        progressDialog.show();
        //
        iruvc = new IRUVC(cameraWidth, cameraHeight, ImageOrTempDisplayActivity.this, syncimage,
                defaultDataFlowMode, isUseIRISP, isUseGPU, new ConnectCallback() {

            @Override
            public void onCameraOpened(UVCCamera uvcCamera) {
                Log.i(TAG, "ConnectCallback->onCameraOpened");
                ImageOrTempDisplayActivity.this.uvcCamera = uvcCamera;
            }

            @Override
            public void onIRCMDCreate(IRCMD ircmd) {
                Log.i(TAG, "ConnectCallback->onIRCMDCreate");
                if (ircmd == null) {
                    return;
                }
                ImageOrTempDisplayActivity.this.ircmd = ircmd;
            }
        }, new USBMonitorCallback() {
            @Override
            public void onAttach() {
            }

            @Override
            public void onGranted() {
            }

            @Override
            public void onConnect() {
            }

            @Override
            public void onDisconnect() {
            }

            @Override
            public void onDettach() {
                finish();
            }

            @Override
            public void onCancel() {
                finish();
            }
        });
        iruvc.setImageSrc(imageSrc);
        iruvc.setRestart(isReStart);
        iruvc.setTemperatureSrc(temperatureSrc);
        iruvc.setRotate(true);
        iruvc.setHandler(mHandler);
        iruvc.registerUSB();
    }

    /**
     *
     */
    private void restartusbcamera() {
        if (iruvc != null) {
            iruvc.unregisterUSB();
            iruvc.stopPreview();
        }
        if (imageThread != null) {
            imageThread.interrupt();
        }
        startUSB(true);
    }

    /**
     * @param position
     */
    private void getDataFlowModeByPosition(int position) {
        if (position == 0) {
            defaultDataFlowMode = CommonParams.DataFlowMode.TEMP_OUTPUT;
        } else if (position == 1) {
            defaultDataFlowMode = CommonParams.DataFlowMode.IR_OUTPUT;
        } else if (position == 2) {
            defaultDataFlowMode = CommonParams.DataFlowMode.KBC_OUTPUT;
        } else if (position == 3) {
            defaultDataFlowMode = CommonParams.DataFlowMode.HBC_DPC_OUTPUT;
        } else if (position == 4) {
            defaultDataFlowMode = CommonParams.DataFlowMode.VBC_OUTPUT;
        } else if (position == 5) {
            defaultDataFlowMode = CommonParams.DataFlowMode.TNR_OUTPUT;
        } else if (position == 6) {
            defaultDataFlowMode = CommonParams.DataFlowMode.SNR_OUTPUT;
        } else if (position == 7) {
            defaultDataFlowMode = CommonParams.DataFlowMode.AGC_OUTPUT;
        } else if (position == 8) {
            defaultDataFlowMode = CommonParams.DataFlowMode.DDE_OUTPUT;
        } else if (position == 9) {
            defaultDataFlowMode = CommonParams.DataFlowMode.GAMMA_OUTPUT;
        } else if (position == 10) {
            defaultDataFlowMode = CommonParams.DataFlowMode.MIRROR_OUTPUT;
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.manualShutButton) {
            //=== 打快门
            if (syncimage.type == 1) {
                ircmd.tiny1bShutterManual();
            } else {
                ircmd.updateOOCOrB(CommonParams.UpdateOOCOrBType.B_UPDATE);
            }
        } else {
            if (view.getId() == R.id.btnImageTemp) {
                /**
                 * 图像+温度
                 */
                defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
            } else if (view.getId() == R.id.btnImage) {
                /**
                 * 图像
                 */
                defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_OUTPUT;
            } else if (view.getId() == R.id.btnY16ModeSet) {
                /**
                 * 中间出图设置
                 */
                int position = binding.ParamY16ModeType.getSelectedItemPosition();
                Log.i(TAG, "position = " + position);
                getDataFlowModeByPosition(position);
            }
            initDataFlowMode(defaultDataFlowMode);
            imageThread.setDataFlowMode(defaultDataFlowMode);
            //
            restartusbcamera();
            startISP();
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.ParamY16ModeType: {
                Log.i(TAG, "position = " + position);
                getDataFlowModeByPosition(position);
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    @Override
    protected void onStart() {
        Log.w(TAG, "onStart");
        super.onStart();
        if (!isrun) {
            startUSB(false);
            startISP();
            binding.cameraView.start();
            isrun = true;
        }
    }

    @Override
    protected void onStop() {
        Log.w(TAG, "onStop");
        super.onStop();
        if (iruvc != null) {
            iruvc.unregisterUSB();
            iruvc.stopPreview();
        }
        imageThread.interrupt();
        syncimage.valid = false;
        binding.cameraView.stop();
        isrun = false;
    }

    @Override
    protected void onDestroy() {
        Log.w(TAG, "onDestroy");
        super.onDestroy();
        try {
            imageThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "imageThread.join(): catch an interrupted exception");
        }
    }

}