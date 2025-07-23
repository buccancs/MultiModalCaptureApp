package com.infisense.usbir.view;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import com.infisense.usbir.databinding.DialogTempCalibrationInputBinding;

/**
 * Created by fengjibo on 2022/12/19.
 */
public class TempCalibrationInputDialog extends AlertDialog {
    private Context mContext;
    private DialogTempCalibrationInputBinding mDialogTempCalibrationInputBinding;
    private OnInputListener mOnInputListener;
    private String mTitleText;
    private String mDefaultText;

    protected TempCalibrationInputDialog(Context context, String title, String defaultText) {
        super(context);
        mContext = context;
        mTitleText = title;
        mDefaultText = defaultText;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        mDialogTempCalibrationInputBinding = DialogTempCalibrationInputBinding.inflate(LayoutInflater.from(mContext));

        setContentView(mDialogTempCalibrationInputBinding.getRoot());
        mDialogTempCalibrationInputBinding.inputTitle.setText(mTitleText);
        mDialogTempCalibrationInputBinding.inputEdit.setText(mDefaultText);

        mDialogTempCalibrationInputBinding.cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        mDialogTempCalibrationInputBinding.confirmButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String inputText = mDialogTempCalibrationInputBinding.inputEdit.getText().toString();

                if (TextUtils.isEmpty(inputText)) {
                    Toast.makeText(mContext, mTitleText, Toast.LENGTH_SHORT).show();
                    return;
                }
                String indexText = mDialogTempCalibrationInputBinding.inputEdit1.getText().toString();

                dismiss();
                if (mOnInputListener != null) {
                    mOnInputListener.onConfirm(inputText, indexText);
                }
            }
        });

    }

    public interface OnInputListener {
        void onCancel();
        void onConfirm(String inputText, String indexText);
    }

    public void setOnInputListener(OnInputListener onInputListener) {
        this.mOnInputListener = onInputListener;
    }

    public void showIndexEditView(String text) {
        mDialogTempCalibrationInputBinding.inputEdit1.setText(text);
        mDialogTempCalibrationInputBinding.inputEdit1.setVisibility(View.VISIBLE);
    }
}
