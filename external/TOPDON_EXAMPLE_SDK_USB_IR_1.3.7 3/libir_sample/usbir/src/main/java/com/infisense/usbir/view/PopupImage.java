package com.infisense.usbir.view;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.infisense.iruvc.ircmd.IRCMD;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.uvc.UVCCamera;
import com.infisense.usbir.MyApplication;
import com.infisense.usbir.R;
import com.infisense.usbir.databinding.LayoutImageBinding;
import com.infisense.usbir.utils.SharedPreferencesUtil;


/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2021.12.9 13:45
 * @UpdateUser:
 * @UpdateDate:     2021.12.9 13:45
 * @UpdateRemark:
 */
public class PopupImage implements CompoundButton.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {

    private Context mContext;
    private static final String TAG = "PopupImage";
    private static final String[] TNRArray = {"0", "1", "2", "3"};
    private static final String[] SNRArray = {"0", "1", "2", "3"};
    private static final String[] DDEArray = {"0", "1", "2", "3", "4"};
    private static final String[] AGCArray = {"0", "1", "2", "3", "4", "5"};
    private PopupWindow popupWindow;
    private LayoutImageBinding imageBinding;
    private IRCMD ircmd;
    private OnRotateListener onRotateListener;
    private ArrayAdapter<String> TNRArrayAdapter, SNRArrayAdapter, DDEArrayAdapter, AGCArrayAdapter;
    private boolean rotate = false;
    private byte[] PROJECT_INFO = new byte[4];
    private boolean isUseIRISP;
    private UVCCamera uvcCamera;
    // 电子变倍
    private float currentScale = 1.0f;

    /**
     *
     */
    public interface OnRotateListener {
        void onRotate(boolean isRotate);
    }

    /**
     * @param parent
     */
    public void showAsDropDown(View parent) {
        popupWindow.showAsDropDown(parent);
        getImageParam();
    }

    /**
     * @param ircmd
     */
    public void setIrcmd(IRCMD ircmd) {
        this.ircmd = ircmd;
    }

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * @param isUseIRISP
     */
    public void setUseIRISP(boolean isUseIRISP) {
        this.isUseIRISP = isUseIRISP;
    }

    /**
     * @param uvcCamera
     */
    public void setUVCCamera(UVCCamera uvcCamera) {
        this.uvcCamera = uvcCamera;
    }

    /**
     * @param context
     * @param onRotateListener
     * @param dismissListener
     */
    public PopupImage(Context context, OnRotateListener onRotateListener,
                      PopupWindow.OnDismissListener dismissListener) {
        this.mContext = context;
        this.onRotateListener = onRotateListener;
        imageBinding = LayoutImageBinding.inflate(LayoutInflater.from(context));
        //
        TNRArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, TNRArray);
        SNRArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, SNRArray);
        DDEArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, DDEArray);
        AGCArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, AGCArray);
        //
        imageBinding.TNR.setAdapter(TNRArrayAdapter);
        imageBinding.SNR.setAdapter(SNRArrayAdapter);
        imageBinding.DDE.setAdapter(DDEArrayAdapter);
        imageBinding.AGC.setAdapter(AGCArrayAdapter);
        View.OnClickListener handler = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int result;
                switch (view.getId()) {
                    case R.id.btnRotate:
                        onRotateListener.onRotate(true);
                        break;
                    case R.id.derotate:
                        onRotateListener.onRotate(false);
                        break;
                    case R.id.mirror:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP,
                                CommonParams.PropImageParamsValue.MirrorFlipType.ONLY_MIRROR);
                        break;
                    case R.id.flip:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP,
                                CommonParams.PropImageParamsValue.MirrorFlipType.ONLY_FLIP);
                        break;
                    case R.id.flip_mirror:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP,
                                CommonParams.PropImageParamsValue.MirrorFlipType.MIRROR_FLIP);
                        break;
                    case R.id.none:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP,
                                CommonParams.PropImageParamsValue.MirrorFlipType.NO_MIRROR_FLIP);
                        break;
                    case R.id.reload:
                        result = ircmd.loadDefaultParams(CommonParams.DefaultConfigParams.PROP_SEL_IMAGE);
                        if (result == 0) {
                            Toast.makeText(mContext, "success,power off and restart", Toast.LENGTH_SHORT).show();
                            getImageParam();
                        } else {
                            Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case R.id.zoomdown:
                            ircmd.zoomCenterDown(CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                                    CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.zoomup:
                            ircmd.zoomCenterUp(CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                                    CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.zoomPositionDown:
                        ircmd.zoomPositionDown(100, 100, CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                                CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.zoomPositionUp:
                        ircmd.zoomPositionUp(100, 100, CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                                CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.setagc: {
                        String MAXGAINStr = imageBinding.MAXGAIN.getText().toString().trim();
                        if (MAXGAINStr.length() != 0) {
                            ircmd.getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_PROJECT_INFO, PROJECT_INFO); //ok
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_MAX_GAIN,
                                    new CommonParams.PropImageParamsValue.NumberType(MAXGAINStr));
                        }
                        String BOSStr = imageBinding.BOS.getText().toString().trim();
                        if (BOSStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BOS,
                                    new CommonParams.PropImageParamsValue.NumberType(BOSStr));
                        }
                        break;
                    }
                    case R.id.setIR: {
                        String CONTRASTStr = imageBinding.CONTRAST.getText().toString().trim();
                        if (CONTRASTStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_CONTRAST,
                                    new CommonParams.PropImageParamsValue.NumberType(CONTRASTStr));
                        }
                        String BRIGHTNESSStr = imageBinding.BRIGHTNESS.getText().toString().trim();
                        if (BRIGHTNESSStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BRIGHTNESS,
                                    new CommonParams.PropImageParamsValue.NumberType(BRIGHTNESSStr));
                        }
                        break;
                    }
                    case R.id.btnSaveConfig: {
                        // save param config
                        result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_ALL);
                        if (result == 0) {
                            Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        };
        imageBinding.btnRotate.setOnClickListener(handler);
        imageBinding.derotate.setOnClickListener(handler);
        imageBinding.mirror.setOnClickListener(handler);
        imageBinding.flip.setOnClickListener(handler);
        imageBinding.flipMirror.setOnClickListener(handler);
        imageBinding.zoomdown.setOnClickListener(handler);
        imageBinding.zoomup.setOnClickListener(handler);
        imageBinding.zoomPositionDown.setOnClickListener(handler);
        imageBinding.zoomPositionUp.setOnClickListener(handler);
        imageBinding.none.setOnClickListener(handler);
        imageBinding.reload.setOnClickListener(handler);
        imageBinding.setIR.setOnClickListener(handler);
        imageBinding.btnSaveConfig.setOnClickListener(handler);
        imageBinding.setagc.setOnClickListener(handler);
        popupWindow = new PopupWindow(imageBinding.getRoot());
        popupWindow.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        popupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popupWindow.setFocusable(true);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setOnDismissListener(dismissListener);
        popupWindow.setBackgroundDrawable(new ColorDrawable(0x00000000)); // 解决 7.0 手机，点击外部不消失
        imageBinding.getRoot().measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        //创建布局管理
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.HORIZONTAL);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.ONOFF_AGC: {
                if (isChecked) {
                    ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC,
                            CommonParams.PropImageParamsValue.StatusSwith.ON);
                } else {
                    ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC,
                            CommonParams.PropImageParamsValue.StatusSwith.OFF);
                }
                break;
            }
            case R.id.switchAutoGain: {
                /**
                 * 自动增益切换功能
                 *  SDK中实现
                 */
                SharedPreferencesUtil.saveData(mContext, MyApplication.getInstance().SWITCH_AUTO_GAIN_KEY, isChecked);
                break;
            }
            case R.id.switchOverProtect: {
                /**
                 * 防灼烧功能
                 */
                /**
                 * 方式1:固件中实现
                 */
//                if (isChecked) {
//                    ircmd.setOverexposureParams(CommonParams.PropOverexposureParams.OVEXP_PROP_SWITCH,
//                            CommonParams.PropOverexposureParamsValue.StatusSwith.ON);
//                } else {
//                    ircmd.setOverexposureParams(CommonParams.PropOverexposureParams.OVEXP_PROP_SWITCH,
//                            CommonParams.PropOverexposureParamsValue.StatusSwith.OFF);
//                }
                /**
                 * 方式2:SDK中实现(推荐)
                 */
                SharedPreferencesUtil.saveData(mContext, MyApplication.getInstance().SWITCH_OVER_PROTECT, isChecked);
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.TNR:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_3);
                        break;
                    }
                    default:
                        break;
                }
                break;
            case R.id.SNR:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_3);
                        break;
                    }
                    default:
                        break;
                }
                break;
            case R.id.DDE:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_3);
                        break;
                    }
                    case 4: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_4);
                        break;
                    }
                    default:
                        break;
                }
                break;
            case R.id.AGC:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_3);
                        break;
                    }
                    case 4: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_4);
                        break;
                    }
                    case 5: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_5);
                        break;
                    }
                    default:
                        break;
                }
                // AGC不同档位都对应了一对MAXGAIN和BOS,也就是说AGC切换档位都应该重新读一下MAXGAIN和BOS,不同档位重新设置后值是不同的
                int[] mode = new int[1];
                ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_MAX_GAIN, mode);
                imageBinding.MAXGAIN.setText(String.valueOf(mode[0]));
                Log.i(TAG, "AGC = " + position + " MAXGAIN = " + mode[0]);
                ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BOS, mode);
                imageBinding.BOS.setText(String.valueOf(mode[0]));
                Log.i(TAG, "AGC = " + position + " BOS = " + mode[0]);
                break;
            default:
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    /**
     *
     */
    private void getImageParam() {
        int[] mode = new int[1];
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        imageBinding.TNR.setOnItemSelectedListener(null);
        imageBinding.TNR.setSelection(mode[0], true);
        imageBinding.TNR.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        imageBinding.SNR.setOnItemSelectedListener(null);
        imageBinding.SNR.setSelection(mode[0], true);
        imageBinding.SNR.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        imageBinding.DDE.setOnItemSelectedListener(null);
        imageBinding.DDE.setSelection(mode[0], true);
        imageBinding.DDE.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        imageBinding.AGC.setOnItemSelectedListener(null);
        imageBinding.AGC.setSelection(mode[0], true);
        imageBinding.AGC.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_MAX_GAIN, mode);
        imageBinding.MAXGAIN.setText(mode[0] + "");
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BOS, mode);
        imageBinding.BOS.setText(mode[0] + "");
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_CONTRAST, mode);
        imageBinding.CONTRAST.setText(mode[0] + "");
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BRIGHTNESS, mode);
        imageBinding.BRIGHTNESS.setText(mode[0] + "");
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC, mode);
        imageBinding.ONOFFAGC.setOnCheckedChangeListener(null);
        imageBinding.ONOFFAGC.setChecked(mode[0] == 1);
        imageBinding.ONOFFAGC.setOnCheckedChangeListener(this);
        /**
         * 自动增益切换功能
         *  SDK中实现
         */
        imageBinding.switchAutoGain.setChecked((boolean) SharedPreferencesUtil.getData(mContext,
                MyApplication.getInstance().SWITCH_AUTO_GAIN_KEY, false));
        imageBinding.switchAutoGain.setOnCheckedChangeListener(this);
        /**
         * 防灼烧功能
         */
        /**
         * 方式1:固件中实现
         */
//        long overValue[] = new long[4];
//        ircmd.getOverexposureParams(CommonParams.PropOverexposureParams.OVEXP_PROP_SWITCH, overValue);
//        imageBinding.switchOverProtect.setChecked(overValue[0] == 1);
        /**
         * 方式2:SDK中实现(推荐)
         * 具体使用见demo示例com.infisense.usbir.camera.IRUVC中的数据处理
         */
        imageBinding.switchOverProtect.setChecked((boolean) SharedPreferencesUtil.getData(mContext,
                MyApplication.getInstance().SWITCH_OVER_PROTECT, false));
        imageBinding.switchOverProtect.setOnCheckedChangeListener(this);
    }

    /**
     * dismiss the popupwindow
     */
    public void dismiss() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }

}
