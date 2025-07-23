package com.infisense.usbir.view;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.PopupWindow;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.alibaba.fastjson.JSON;
import com.infisense.iruvc.ircmd.IRCMD;
import com.infisense.iruvc.ircmd.IRCMDType;
import com.infisense.iruvc.ircmd.IRUtils;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.CommonUtils;
import com.infisense.usbir.MyApplication;
import com.infisense.usbir.R;
import com.infisense.usbir.databinding.LayoutOthersBinding;
import com.infisense.usbir.utils.FileUtil;
import com.infisense.usbir.utils.SharedPreferencesUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * @Description:
 * @Author: brilliantzhao
 * @CreateDate: 2021.12.9 13:45
 * @UpdateUser:
 * @UpdateDate: 2021.12.9 13:45
 * @UpdateRemark:
 */
public class PopupOthers implements View.OnClickListener, CompoundButton.OnCheckedChangeListener,
        AdapterView.OnItemSelectedListener {

    private static final String TAG = "PopupOthers";
    private static final String COLOR_DATA = "color_data.bin";
    public static final String COLOR_RGB_DATA_INT = "color_rgb_data_int.txt"; // RGB数据
    public static final String COLOR_YUV_DATA_INT = "color_yuv_data_int.txt"; // YUV数据
    private static final String[] tpdtype = {"TPD_PROP_DISTANCE", "TPD_PROP_TU", "TPD_PROP_TA", "TPD_PROP_EMS",
            "TPD_PROP_TAU", "TPD_PROP_GAIN_SEL", "TPD_PROP_P0", "TPD_PROP_P1", "TPD_PROP_P2"};
    private static final String[] saveConfigArray = {"NO EDIT", "SPI_MOD_CFG_ALL", "SPI_MOD_CFG_DEAD_PIX",
            "SPI_MOD_CFG_PROPERTY_PAGE"};
    private static final String[] restoreConfigArray = {"NO EDIT", "DEF_CFG_ALL", "DEF_CFG_TPD", "DEF_CFG_PROP_PAGE",
            "DEF_CFG_USER_CFG"};
    private IRCMD ircmd;
    private PopupWindow popupWindow;
    private LayoutOthersBinding othersBinding;
    private Context mContext;
    // 当前的增益状态
    private CommonParams.GainStatus gainStatus = CommonParams.GainStatus.HIGH_GAIN;
    // 模组支持的高低增益模式
    private CommonParams.GainMode gainMode = CommonParams.GainMode.GAIN_MODE_HIGH_LOW;
    private byte[] tau_data_H;
    private byte[] tau_data_L;
    private long tempinfo = 0; // 用于测温修正的temp_cal_info
    private boolean isGetNucFromFlash; // 是否从机芯Flash中读取的nuc数据，会影响到测温修正的资源释放
    private short[] nuc_table_high = new short[8192];
    private short[] nuc_table_low = new short[8192];
    private int[] param = new int[9];
    private ArrayAdapter<String> saveConfigAdapter, restoreConfigAdapter;
    // progressDialog
    private AlertDialog progressDialog;
    // 要测试的模组类型
    private CommonParams.ProductType productType = CommonParams.ProductType.P2;
    // deviceType
    private static final String[] spnProductTypeArray = {"TINY1B", "TINY1C", "TINY1BE", "MINI256", "MINI384",
            "MINI640", "P2"};
    private ArrayAdapter<String> spnProductTypeAdapter;
    /**
     * 供测试无设备下温度二次修正接口
     * 需要首次使用为true,连接模组，读取模组中的测温信息并保存下来
     * 以后才可以设置为false,即本地已经存储的有模组中的数据，可以使用离线测温
     */
    private boolean deviceConnected = true;
    private String md5PNSNKey; //根据模组的SN信息作为模组信息保存的key参数
    //
    private final int MESSAGE_CODE_READ_NUC_SUCCESS = 1000;
    private final int MESSAGE_CODE_RESTORE_CONFIG_SUCCESS = 1001;
    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == MESSAGE_CODE_READ_NUC_SUCCESS) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                Toast.makeText(mContext, "read nuc success", Toast.LENGTH_SHORT).show();
            } else if (msg.what == MESSAGE_CODE_RESTORE_CONFIG_SUCCESS) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                int result = (int) msg.obj;
                if (result == 0) {
                    if (msg.arg1 == 1) {
                        Toast.makeText(mContext, mContext.getResources().getString(R.string.restore_restart),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                    }

                } else {
                    Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    /**
     * @param context
     * @param dismissListener
     */
    public PopupOthers(Context context, PopupWindow.OnDismissListener dismissListener) {
        this.mContext = context;
        othersBinding = LayoutOthersBinding.inflate(LayoutInflater.from(context));
        spnProductTypeAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, spnProductTypeArray);
        othersBinding.spnProductType.setAdapter(spnProductTypeAdapter);
        //
        popupWindow = new PopupWindow(othersBinding.getRoot());
        popupWindow.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        popupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popupWindow.setFocusable(true);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setOnDismissListener(dismissListener);
        popupWindow.setBackgroundDrawable(new ColorDrawable(0x00000000)); // 解决 7.0 手机，点击外部不消失
        othersBinding.getRoot().measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        //创建布局管理
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.HORIZONTAL);
        //
        othersBinding.btnTempCorrection1.setOnClickListener(this);
        othersBinding.btnTempCorrection2.setOnClickListener(this);
        othersBinding.btnTempCorrection3.setOnClickListener(this);
        othersBinding.btnShutSubmit.setOnClickListener(this);
        othersBinding.btnTpdSubmit.setOnClickListener(this);
        othersBinding.btnFPS.setOnClickListener(this);
        //
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, tpdtype);
        othersBinding.ParamSel.setAdapter(adapter);
        othersBinding.ParamSel.setOnItemSelectedListener(this);
        //
        saveConfigAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, saveConfigArray);
        othersBinding.saveConfig.setAdapter(saveConfigAdapter);
        othersBinding.saveConfig.setOnItemSelectedListener(this);
        restoreConfigAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, restoreConfigArray);
        othersBinding.restoreConfig.setAdapter(restoreConfigAdapter);
        othersBinding.restoreConfig.setOnItemSelectedListener(this);
        //
        othersBinding.btnColorPseudocolor.setOnClickListener(this);
        othersBinding.btnPseudocolorConvert.setOnClickListener(this);

        othersBinding.spnProductType.setSelection(6, true);
        othersBinding.spnProductType.setOnItemSelectedListener(this);
    }

    /**
     * @param parent
     */
    public void showAsDropDown(View parent) {
        popupWindow.showAsDropDown(parent);
        if (ircmd != null) {
            getImageParam();
            int[] currentVTemperature = new int[1];
            ircmd.getCurrentVTemperature(currentVTemperature);
            othersBinding.tvDTEMP.setText(mContext.getResources().getString(R.string.currentVTemp) + currentVTemperature[0]);
            othersBinding.etFPS.setSelection(othersBinding.etFPS.getText().length());
            //
        }
        //

        othersBinding.ParamSel.setSelection(0);
        othersBinding.saveConfig.setSelection(0);
        othersBinding.restoreConfig.setSelection(0);
    }

    /**
     * @param ircmd
     */
    public void setIrcmd(IRCMD ircmd) {
        this.ircmd = ircmd;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btnTempCorrection1: {
                // Temperature correction step 1
                /**
                 * 环境变量校准参数导入测试代码，该方法比较耗时，需要在子线程中执行，且只需要执行一次,
                 * 和releaseTemperatureCorrection方法成对出现
                 * @warring 如果是P2模组，则只需要执行step2,tempinfo可以传入0
                 */
                if (progressDialog == null) {
                    initProgressDialog();
                }
                progressDialog.show();
                // 是否使用assets中的数据，方便测试查看效果和验证问题
                boolean isUseAssetsData = false;
                Log.i(TAG, "isUseSaveData = " + isUseAssetsData + " deviceConnected = " + deviceConnected);
                //
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        //
                        if (deviceConnected) {
                            AssetManager am = mContext.getAssets();
                            InputStream is = null;
                            try {
                                // 根据不同的高低增益加载不同的等效大气透过率表
                                int[] value = new int[1];
                                ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, value);
                                Log.d(TAG, "TPD_PROP_GAIN_SEL=" + value[0]);
                                if (value[0] == 1) {
                                    // 当前机芯为高增益
                                    gainStatus = CommonParams.GainStatus.HIGH_GAIN;
                                    // 等效大气透过率表
                                } else {
                                    // 当前机芯为低增益
                                    gainStatus = CommonParams.GainStatus.LOW_GAIN;
                                }
                                /**
                                 * 这里需要根据自己的实际镜头尺寸来加载对应的文件
                                 * 如：91:9.1mm镜头
                                 *    135:13.5mm镜头
                                 * @warring 这里的tau表仅为示例，实际项目中请替换成自己的
                                 */
                                tau_data_H = CommonUtils.getTauData(mContext, "tau/tau_H.bin");
                                tau_data_L = CommonUtils.getTauData(mContext, "tau/tau_L.bin");
                                Log.i(TAG, "tau_data_H[" + 1000 + "]=" + tau_data_H[1000] +
                                        " tau_data_L[" + 1000 + "]=" + tau_data_L[1000]);

                                /*
                                 * readNucTableFromFlash为耗时操作，影响用户体验
                                 * 这里的优化逻辑为：以SN码为唯一标识，首次读取nuc table信息后存储起来，
                                 * 下次进来之后判断是否存在nuc table文件，存在则直接使用；否则则重新读取并存储
                                 */
                                byte[] SN = new byte[16];
                                ircmd.getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_GET_SN, SN);
                                String deviceSNUnCodePath =
                                        MyApplication.getInstance().DEVICE_DATA_SAVE_DIR + File.separator;
                                // 使用模组的唯一信息作为key,避免多个模组插拔造成的数据问题
                                md5PNSNKey = FileUtil.getMD5Key(new String(SN));
                                SharedPreferencesUtil.saveData(mContext, "md5PNSNKey", md5PNSNKey);
                                //
                                String nucHighFileName = md5PNSNKey + "_nuc_table_high.bin";
                                String nucLowFileName = md5PNSNKey + "_nuc_table_low.bin";
                                if (isUseAssetsData || !md5PNSNKey.isEmpty() && FileUtil.isFileExists(mContext,
                                        deviceSNUnCodePath + nucHighFileName) &&
                                        FileUtil.isFileExists(mContext, deviceSNUnCodePath + nucLowFileName) &&
                                        ContextCompat.checkSelfPermission(mContext,
                                                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                    Log.i(TAG,
                                            "已经存在文件:" + (isUseAssetsData ? "从assets文件夹下读取 " : "从SD卡上读取 ") + nucHighFileName + "\n" + nucLowFileName);
                                    if (isUseAssetsData) {
                                        // 从assets文件夹下读取
                                        is = am.open("nuc_table_high.bin");
                                        int lenthNuc = is.available();
                                        byte nuc_table_high_byte[] = new byte[lenthNuc];
                                        if (is.read(nuc_table_high_byte) != lenthNuc) {
                                            Log.d(TAG, "read nuc_table_high file fail ");
                                        }
                                        Log.d(TAG, "read nuc_table_high file lenth " + lenthNuc);
                                        nuc_table_high = FileUtil.toShortArray(nuc_table_high_byte);
                                        //
                                        is = am.open("nuc_table_high.bin");
                                        lenthNuc = is.available();
                                        byte nuc_table_low_byte[] = new byte[lenthNuc];
                                        if (is.read(nuc_table_low_byte) != lenthNuc) {
                                            Log.d(TAG, "read nuc_table_low file fail ");
                                        }
                                        Log.d(TAG, "read nuc_table_low file lenth " + lenthNuc);
                                        nuc_table_low = FileUtil.toShortArray(nuc_table_low_byte);
                                    } else {
                                        // 从SD卡上读取
                                        byte[] nuc_table_high_byte = FileUtil.readFile2BytesByStream(mContext,
                                                new File(deviceSNUnCodePath + nucHighFileName));
                                        nuc_table_high = FileUtil.toShortArray(nuc_table_high_byte);
                                        //
                                        byte[] nuc_table_low_byte = FileUtil.readFile2BytesByStream(mContext,
                                                new File(deviceSNUnCodePath + nucLowFileName));
                                        nuc_table_low = FileUtil.toShortArray(nuc_table_low_byte);
                                    }

                                    /**
                                     * 获取机芯中的环境变量参数
                                     * 该参数请区分当前测温环境的参数
                                     * 该部分参数可以获取之后存储在本地，不用每次都获取
                                     */
                                    int[] orgEMS = new int[1];
                                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_EMS, orgEMS);
                                    int[] orgTAU = new int[1];
                                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TAU, orgTAU);
                                    int[] orgTA = new int[1];
                                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TA, orgTA);
                                    int[] orgTU = new int[1];
                                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TU, orgTU);

                                    isGetNucFromFlash = false;
                                    /**
                                     * 获取tempinfo
                                     * 允许不插入模组时使用
                                     * 其中的数据请获取后进行保存,方便下次直接使用
                                     */
                                    tempinfo = IRUtils.getTemperatureCorrectionTempCalInfo(IRCMDType.USB_IR_256_384,
                                            gainMode, gainStatus, nuc_table_high, nuc_table_low,
                                            orgEMS[0], orgTAU[0], orgTA[0], orgTU[0]);
                                } else {
                                    /**
                                     * 获取机芯中的测温修正数据nuc_table以及tempinfo
                                     */
                                    Log.i(TAG, "从机芯里面读取并保存:" + nucHighFileName + "\n" + nucLowFileName);
                                    if (ircmd != null && !md5PNSNKey.isEmpty() &&
                                            ContextCompat.checkSelfPermission(mContext,
                                                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                        isGetNucFromFlash = true;
                                        tempinfo = ircmd.readNucTableFromFlash(gainMode, gainStatus, nuc_table_high,
                                                nuc_table_low);
                                        // 保存数据，方便查看，可按照需要确定是否保存
                                        FileUtil.saveShortFileForDeviceData(nuc_table_high, nucHighFileName);
                                        FileUtil.saveShortFileForDeviceData(nuc_table_low, nucLowFileName);
                                    }
                                }
                                // 获取nuc_table表数据
                                for (int i = 0; i < nuc_table_low.length; i += 1000) {
                                    Log.i(TAG,
                                            "nuc_table_high[" + i + "]=" + nuc_table_high[i] + " nuc_table_low[" + i +
                                                    "]=" + nuc_table_low[i]);
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            } finally {
                                try {
                                    if (is != null) {
                                        is.close();
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        } else {
                            String md5PNSNKey = (String) SharedPreferencesUtil.getData(mContext, "md5PNSNKey", "");
                            if (TextUtils.isEmpty(md5PNSNKey)) {
                                return;
                            }
                            String deviceSNUnCodePath =
                                    MyApplication.getInstance().DEVICE_DATA_SAVE_DIR + File.separator;
                            String nucHighFileName = md5PNSNKey + "_nuc_table_high.bin";
                            String nucLowFileName = md5PNSNKey + "_nuc_table_low.bin";

                            gainStatus = CommonParams.GainStatus.LOW_GAIN;
                            /**
                             * 这里需要根据自己的实际镜头尺寸来加载对应的文件
                             * 如：91:9.1mm镜头
                             *    135:13.5mm镜头
                             * @warring 这里的tau表仅为示例，实际项目中请替换成自己的
                             */
                            tau_data_H = CommonUtils.getTauData(mContext, "tau/tau_H.bin");
                            tau_data_L = CommonUtils.getTauData(mContext, "tau/tau_L.bin");
                            Log.i(TAG, "tau_data_H[" + 1000 + "]=" + tau_data_H[1000] +
                                    " tau_data_L[" + 1000 + "]=" + tau_data_L[1000]);

                            // 从SD卡上读取
                            byte[] nuc_table_high_byte = FileUtil.readFile2BytesByStream(mContext,
                                    new File(deviceSNUnCodePath + nucHighFileName));
                            nuc_table_high = FileUtil.toShortArray(nuc_table_high_byte);
                            //
                            byte[] nuc_table_low_byte = FileUtil.readFile2BytesByStream(mContext,
                                    new File(deviceSNUnCodePath + nucLowFileName));
                            nuc_table_low = FileUtil.toShortArray(nuc_table_low_byte);

                            // 获取nuc_table表数据
                            for (int i = 0; i < nuc_table_low.length; i += 1000) {
                                Log.i(TAG, "nuc_table_high[" + i + "]=" + nuc_table_high[i] + " nuc_table_low[" + i +
                                        "]=" + nuc_table_low[i]);
                            }
                            isGetNucFromFlash = false;

                            int orgEMS = 128;
                            int orgTAU = 128;
                            int orgTA = 300;
                            int orgTU = 300;
                            /**
                             * 获取tempinfo
                             * 允许不插入模组时使用
                             * 其中的数据请获取后进行保存,方便下次直接使用
                             */
                            tempinfo = IRUtils.getTemperatureCorrectionTempCalInfo(IRCMDType.USB_IR_256_384,
                                    gainMode, gainStatus, nuc_table_high, nuc_table_low,
                                    orgEMS, orgTAU, orgTA, orgTU);

                        }

                        //
                        Message message = new Message();
                        message.what = MESSAGE_CODE_READ_NUC_SUCCESS;
                        mHandler.sendMessage(message);
                    }
                }).start();
                break;
            }
            case R.id.btnTempCorrection2: {
                /**
                 * 环境变量修正，可以执行多次，对多个温度进行修正
                 * 环境变量校准之前的温度，单位为摄氏度
                 * 请事先确认好红外模组的产品类型，例如:是P2模组，ProductType参数请传入CommonParams.ProductType.P2
                 */
                /**
                 * 更新测温二次修正参数
                 * 增益切换，更改EMS，TAU，Ta，Tu之后需要调用
                 * 如果没有更改，则无需调用，请参考实际情况
                 */
//                tempinfo = ircmd.updateOrgEnvParam(gainStatus, tempinfo);
                /**
                 * 为方便调试，概述输入弹框的形式
                 */
                TempCalibrationInputDialog tempInputDialog = new TempCalibrationInputDialog(mContext,
                        mContext.getString(R.string.input_correct_param),
                        "40.0\n1.0\n27.0\n27.0\n0.25\n0.8");
                tempInputDialog.show();
                tempInputDialog.setOnInputListener(new TempCalibrationInputDialog.OnInputListener() {
                    @Override
                    public void onCancel() {

                    }

                    @Override
                    public void onConfirm(String inputText, String indexText) {
                        String[] params = inputText.replaceAll("\n", ";").split(";");
                        float[] params_array = new float[params.length];
                        Log.d(TAG, "params_array length = " + params_array.length);
                        for (int i = 0; i < params.length; i++) {
                            params_array[i] = Float.parseFloat(params[i]);
                        }
                        // 单点修正过程
                        tempCorrect(params_array);
                    }
                });
                break;
            }
            case R.id.btnTempCorrection3: {
                // Temperature correction step 3
                /**
                 * 释放资源，和readNucTableFromFlash方法成对出现，且只需要执行一次
                 * @warring 如果是P2模组，则只需要执行step2,tempinfo可以传入0
                 */
                Log.i(TAG, "releaseTemperatureCorrection-start");
                if (tempinfo != 0) {
                    IRUtils.releaseTemperatureCorrection(IRCMDType.USB_IR_256_384, tempinfo, isGetNucFromFlash);
                }
                Log.i(TAG, "releaseTemperatureCorrection-end");
                break;
            }
            case R.id.btnFPS: {
                // 输出帧频调节
                int fpsNum = Integer.parseInt(othersBinding.etFPS.getText().toString().trim());
                if (fpsNum < 1 || fpsNum > 30) {
                    Toast.makeText(mContext, "输出帧频范围1~30", Toast.LENGTH_SHORT).show();
                    return;
                }
                ircmd.setPreviewFPS((char) fpsNum);
                break;
            }
            case R.id.btnShutSubmit: {
                // 自动快门参数设置
                String minValue = othersBinding.min.getText().toString().trim();
                if (minValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MIN_INTERVAL,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(minValue));
                }
                String maxValue = othersBinding.max.getText().toString().trim();
                if (maxValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MAX_INTERVAL,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(maxValue));
                }
                String oocValue = othersBinding.ooc.getText().toString().trim();
                if (oocValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_OOC,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(oocValue));
                }
                String bValue = othersBinding.b.getText().toString().trim();
                if (bValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_B,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(bValue));
                }
                String anyIntervalValue = othersBinding.anyIntervalEdit.getText().toString().trim();
                if (anyIntervalValue.length() != 0) {
                    int status =
                            ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_ANY_INTERVAL,
                                    new CommonParams.PropAutoShutterParameterValue.NumberType(anyIntervalValue));
                    Log.d(TAG, "anyIntervalValue status : " + status);
                }
                String highProtectValue = othersBinding.highProtectEdit.getText().toString().trim();
                if (highProtectValue.length() != 0) {
                    int status =
                            ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROTECT_THR_HIGH_GAIN,
                                    new CommonParams.PropAutoShutterParameterValue.NumberType(highProtectValue));
                    Log.d(TAG, "highProtectValue status : " + status);
                }
                String lowProtectValue = othersBinding.lowProtectEdit.getText().toString().trim();
                if (lowProtectValue.length() != 0) {
                    int status =
                            ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROTECT_THR_LOW_GAIN,
                                    new CommonParams.PropAutoShutterParameterValue.NumberType(lowProtectValue));
                    Log.d(TAG, "highProtectValue status : " + status);
                }
                break;
            }
            case R.id.btnTpdSubmit: {
                //
                String value = othersBinding.data.getText().toString().trim();
                if (value.length() != 0) {
                    param[othersBinding.ParamSel.getSelectedItemPosition()] = (char) Integer.parseInt(value);
                    switch (othersBinding.ParamSel.getSelectedItemPosition()) {
                        case 0: {
                            /// Distance property. unit:cnt(128cnt=1m), range:0-25600(0-200m)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_DISTANCE,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 1: {
                            /// Reflection temperature property. unit:K, range:230-500(high gain), 230-900(low gain)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TU,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 2: {
                            /// Atmospheric temperature property. unit:K, range:230-500(high gain), 230-900(low gain)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TA,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 3: {
                            /// Emissivity property. unit:1/128, range:1-128(0.01-1)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_EMS,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 4: {
                            /// Atmospheric transmittance property. unit:1/128, range:1-128(0.01-1)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TAU,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 5: {
                            /// Gain select. 0:low gain, 1:high gain
                            if (value.equals("0") || value.equals("1")) {
                                if (Integer.parseInt(value) == 0) {
                                    ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
                                            CommonParams.PropTPDParamsValue.GAINSELStatus.GAIN_SEL_LOW);
                                } else {
                                    ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
                                            CommonParams.PropTPDParamsValue.GAINSELStatus.GAIN_SEL_HIGH);
                                }
                            } else {
                                Toast.makeText(mContext, "Gain value is 0 or 1", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        }
                        case 6: {
                            //
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P0,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 7: {
                            //
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P1,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 8: {
                            //
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P2,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        default:
                            break;
                    }
                }
                break;
            }
            case R.id.btnColorPseudocolor: {
                /**
                 * 生成自定义伪彩表
                 * 如：int[][] color1 = new int[][]{{0}, {0, 0, 40}};
                 *     {0}表示0~255的坐标位置
                 *     {0, 0, 40}表示该坐标位置上的R,G,B色值
                 */
                int[][] color1 = new int[][]{{0}, {0, 0, 40}};
                int[][] color2 = new int[][]{{21}, {138, 20, 150}};
                int[][] color3 = new int[][]{{42}, {248, 135, 0}};
                int[][] color4 = new int[][]{{209}, {208, 48, 75}};
                int[][] color5 = new int[][]{{230}, {249, 143, 0}};
                int[][] color6 = new int[][]{{255}, {255, 255, 196}};
                byte[] pseudoDataByte = CommonUtils.generatePseudocolorData(color1, color2, color3, color4, color5,
                        color6);
                //
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + mContext.getPackageName()));
                        ((Activity) mContext).startActivityForResult(intent, 0);
                        return;
                    }
                }
                String path = FileUtil.getSaveFilePath(mContext);
                try {
                    FileUtil.makeDirectory(path);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                // 存储到本地，可以查看生成的伪彩表文件，copy出来给其它的项目使用
                FileUtil.writeTxtToFile(pseudoDataByte, path, COLOR_DATA);
                Log.d(TAG, "file path : " + path + COLOR_DATA);
                break;
            }
            case R.id.btnPseudocolorConvert: {
                // 伪彩表格式转换
                byte[] pseudoDataByte = new byte[768]; // 伪彩数据,长度固定
                String path = FileUtil.getSaveFilePath(mContext);
                /**
                 * 方式1：从文件中读取
                 * 使用上一步“生成自定义伪彩表”中生成的伪彩数据
                 */
                File file = new File(path + COLOR_DATA);
                if (file.exists()) {
                    pseudoDataByte = FileUtil.readFile2BytesByStream(mContext, file);
                } else {
                    Toast.makeText(mContext, "文件不存在", Toast.LENGTH_SHORT).show();
                }
                /**
                 * 方式2：从assets中读取
                 * 使用提前存储好的伪彩数据
                 */
//                AssetManager am = mContext.getAssets();
//                InputStream is;
//                try {
//                    is = am.open("pseudocolor/White_Hot.bin");
//                    int lenth = is.available();
//                    pseudoDataByte = new byte[lenth];
//                    if (is.read(pseudoDataByte) != lenth) {
//                        Log.e(TAG, "read file fail ");
//                    }
//                    //
//                    is.close();
//                } catch (IOException e) {
//                    e.printStackTrace();
//                }

                int index0 = 740, index1 = 750; // 用以检验数据的索引位置
                Log.i(TAG,
                        "pseudoDataByte.lenth = " + pseudoDataByte.length + " " + pseudoDataByte[index0] + " " + pseudoDataByte[index1]);
                /**
                 * 格式转换
                 */
                // RGB伪彩
                int[][] pseudoRGBDataInt = CommonUtils.convertRGBPseudocolorData(pseudoDataByte);
                Log.i(TAG,
                        "pseudoRGBDataInt.lenth = " + pseudoRGBDataInt.length + " " + pseudoRGBDataInt[index0 / 3][index0 % 3] + " " + pseudoRGBDataInt[index1 / 3][index1 % 3]);
                // YUV伪彩
                int[][] pseudoYUVDataInt = CommonUtils.convertYUVPseudocolorData(pseudoDataByte);
                Log.i(TAG,
                        "pseudoYUVDataInt.lenth = " + pseudoYUVDataInt.length + " " + pseudoYUVDataInt[index0 / 3][index0 % 3] + " " + pseudoYUVDataInt[index1 / 3][index1 % 3]);
                /**
                 * 数据存储到本地
                 */
                // RGB伪彩
//                String pseudoRGBDataIntStr = Arrays.deepToString(pseudoRGBDataInt); // 系统方法转换
                String pseudoRGBDataIntStr = JSON.toJSON(pseudoRGBDataInt).toString(); // fastjson方式转换
                Log.d(TAG, "pseudoRGBDataIntStr : " + pseudoRGBDataIntStr);
                FileUtil.saveStringToFile(pseudoRGBDataIntStr, path + COLOR_RGB_DATA_INT);
                Log.d(TAG, "pseudoRGB file path : " + path + COLOR_RGB_DATA_INT);
                // YUV伪彩
                String pseudoYUVDataIntStr = JSON.toJSON(pseudoYUVDataInt).toString(); // fastjson方式转换
                Log.d(TAG, "pseudoYUVDataIntStr : " + pseudoYUVDataIntStr);
                FileUtil.saveStringToFile(pseudoYUVDataIntStr, path + COLOR_YUV_DATA_INT);
                Log.d(TAG, "pseudoYUV file path : " + path + COLOR_YUV_DATA_INT);
                /**
                 * 从本地读取数组
                 */
                // RGB伪彩
                String pseudoRGBDataIntStr2 = FileUtil.getStringFromFile(path + COLOR_RGB_DATA_INT);
                Log.d(TAG, "pseudoRGBDataIntStr2 : " + pseudoRGBDataIntStr2);
                int[][] pseudoRGBDataInt2 = JSON.parseObject(pseudoRGBDataIntStr2, int[][].class);
                Log.i(TAG,
                        "pseudoRGBDataInt2.lenth = " + pseudoRGBDataInt2.length + " " + pseudoRGBDataInt2[index0 / 3][index0 % 3] + " " + pseudoRGBDataInt2[index1 / 3][index1 % 3]);
                // YUV伪彩
                String pseudoYUVDataIntStr2 = FileUtil.getStringFromFile(path + COLOR_YUV_DATA_INT);
                Log.d(TAG, "pseudoYUVDataIntStr2 : " + pseudoYUVDataIntStr2);
                int[][] pseudoYUVDataInt2 = JSON.parseObject(pseudoYUVDataIntStr2, int[][].class);
                Log.i(TAG,
                        "pseudoYUVDataInt2.lenth = " + pseudoYUVDataInt2.length + " " + pseudoYUVDataInt2[index0 / 3][index0 % 3] + " " + pseudoYUVDataInt2[index1 / 3][index1 % 3]);
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.automode: {
                // 自动快门
                if (isChecked) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH,
                            CommonParams.PropAutoShutterParameterValue.StatusSwith.ON);
                } else {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH,
                            CommonParams.PropAutoShutterParameterValue.StatusSwith.OFF);
                }
                break;
            }
            case R.id.protectSwitch: {
                if (isChecked) {
                    int status =
                            ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_PROTECT_SWITCH,
                                    CommonParams.PropAutoShutterParameterValue.StatusSwith.ON);
                    Log.d(TAG, "protectSwitch status = " + status);
                } else {
                    int status =
                            ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_PROTECT_SWITCH,
                                    CommonParams.PropAutoShutterParameterValue.StatusSwith.OFF);
                    Log.d(TAG, "protectSwitch status = " + status);
                }
            }
            default:
                break;
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.Param_Sel: {
                othersBinding.data.setText(param[position] + "");
                othersBinding.data.setSelection((param[position] + "").length());
                break;
            }
            case R.id.saveConfig: {
                if (position != 0) {
                    int result = 0;
                    switch (position) {
                        case 1:
                            result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_ALL);
                            break;
                        case 2:
                            result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_DEAD_PIX);
                            break;
                        case 3:
                            result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_PROPERTY_PAGE);
                            break;
                        default:
                            break;
                    }
                    if (result == 0) {
                        Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            }
            case R.id.restoreConfig: {
                if (position != 0) {
                    if (progressDialog == null) {
                        initProgressDialog();
                    }
                    progressDialog.show();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            boolean isNeedRestart = false;
                            int result = 0;
                            switch (position) {
                                case 1:
                                    // 需要重启生效
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_ALL);
                                    isNeedRestart = true;
                                    break;
                                case 2:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD);
                                    break;
                                case 3:
                                    // 需要重启生效
                                    result =
                                            ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_PROP_PAGE);
                                    isNeedRestart = true;
                                    break;
                                case 4:
                                    result =
                                            ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_USER_CFG);
                                    break;
                                default:
                                    break;
                            }
                            //
                            Message message = new Message();
                            message.what = MESSAGE_CODE_RESTORE_CONFIG_SUCCESS;
                            message.obj = result;
                            message.arg1 = isNeedRestart ? 1 : 0;
                            mHandler.sendMessage(message);
                        }
                    }).start();
                }
                break;
            }
            case R.id.spnProductType:
                switch (position) {
                    case 0: {
                        productType = CommonParams.ProductType.TINY1B;
                        break;
                    }
                    case 1: {
                        productType = CommonParams.ProductType.TINY1C;
                        break;
                    }
                    case 2: {
                        productType = CommonParams.ProductType.TINY1BE;
                        break;
                    }
                    case 3: {
                        productType = CommonParams.ProductType.MINI256;
                        break;
                    }
                    case 4: {
                        productType = CommonParams.ProductType.MINI384;
                        break;
                    }
                    case 5: {
                        productType = CommonParams.ProductType.MINI640;
                        break;
                    }
                    case 6: {
                        productType = CommonParams.ProductType.P2;
                        break;
                    }
                    default:
                        break;
                }
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
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH, mode);
        othersBinding.automode.setOnCheckedChangeListener(null);
        othersBinding.automode.setChecked(mode[0] == 1);
        othersBinding.automode.setOnCheckedChangeListener(this);
        //
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MIN_INTERVAL, mode);
        othersBinding.min.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MAX_INTERVAL, mode);
        othersBinding.max.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_OOC, mode);
        othersBinding.ooc.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_B, mode);
        othersBinding.b.setText(mode[0] + "");

        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_ANY_INTERVAL, mode);
        Log.d(TAG, "SHUTTER_PROP_ANY_INTERVAL status : " + mode[0]);
        othersBinding.anyIntervalEdit.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROTECT_THR_HIGH_GAIN, mode);
        Log.d(TAG, "SHUTTER_PROTECT_THR_HIGH_GAIN status : " + mode[0]);
        othersBinding.highProtectEdit.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROTECT_THR_LOW_GAIN, mode);
        Log.d(TAG, "SHUTTER_PROTECT_THR_LOW_GAIN status : " + mode[0]);
        othersBinding.lowProtectEdit.setText(mode[0] + "");

        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_PROTECT_SWITCH, mode);
        othersBinding.protectSwitch.setOnCheckedChangeListener(null);
        othersBinding.protectSwitch.setChecked(mode[0] == 1);
        othersBinding.protectSwitch.setOnCheckedChangeListener(this);
        //
        int i = 0;
        int[] value = new int[1];
        for (; i < 9; i++) {
            switch (i) {
                case 0: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_DISTANCE, value);
                    break;
                }
                case 1: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TU, value);
                    break;
                }
                case 2: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TA, value);
                    break;
                }
                case 3: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_EMS, value);
                    break;
                }
                case 4: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TAU, value);
                    break;
                }
                case 5: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, value);
                    break;
                }
                case 6: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P0, value);
                    break;
                }
                case 7: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P1, value);
                    break;
                }
                case 8: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P2, value);
                    break;
                }
                default:
                    break;
            }
            param[i] = value[0];
        }
    }

    /**
     * dismiss the popupwindow
     */
    public void dismiss() {
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
    }

    /**
     * 单点修正过程
     *
     * @param params_array
     */
    private void tempCorrect(float[] params_array) {
        float newTemp = IRUtils.temperatureCorrection(IRCMDType.USB_IR_256_384, productType, params_array[0],
                tau_data_H, tau_data_L, params_array[1], params_array[2], params_array[3], params_array[4],
                params_array[5], tempinfo, gainStatus);
        Log.i(TAG,
                "temp correct, oldTemp = " + params_array[0] + " ems = " + params_array[1] + " ta = " + params_array[2] + " " +
                        "distance = " + params_array[4] + " hum = " + params_array[5] + " productType = " + productType + " " +
                        "newtemp = " + newTemp);

        Toast.makeText(mContext, "correct temp is : " + newTemp, Toast.LENGTH_LONG).show();
    }

    /**
     * 初始化ProgressDialog
     */
    private void initProgressDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setView(R.layout.layout_loading);
        builder.setCancelable(true);
        progressDialog = builder.create();
    }
}
