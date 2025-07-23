# 项目简介

USB协议P2,tiny1C,tinyBE,mini256 SDK对应demo，主要用于SDK功能和接口调用展示，方便开发者熟悉和了解SDK及对应接口。

# 出图流程

本流程以libir_sample中的示例为基础进行讲解，您也可以按照您自己的理解实现自己的流程。

## Step1 

在文件`IRUVC.java`中，`USBMonitor`监听设备连接，当插入USB设备的时候，会进入到`onAttach`回调，在其中进行设备pid过滤(用户可以设置自己的过滤白名单)，如果在白名单中，则进行权限申请。

```java
// 注意：USBMonitor的所有回调函数都是运行在线程中的
mUSBMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {

    // called by checking usb device
    // do request device permission
    @Override
    public void onAttach(UsbDevice device) {
        Log.w(TAG, "onAttach");
        if (uvcCamera == null || !uvcCamera.getOpenStatus()) {
            mUSBMonitor.requestPermission(device);
        }
    }

    // called by connect to usb camera
    // do open camera,start previewing
    @Override
    public void onConnect(final UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
        Log.w(TAG, "onConnect");
        if (createNew) {
            openUVCCamera(ctrlBlock, dataFlowMode);
            startPreview();
        }
    }

    // called by disconnect to usb camera
    // do nothing
    @Override
    public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
        Log.w(TAG, "onDisconnect");
    }

    // called by taking out usb device
    // do close camera
    @Override
    public void onDettach(UsbDevice device) {
        Log.w(TAG, "onDettach");
        if (uvcCamera != null && uvcCamera.getOpenStatus()) {
            stopPreview();
        }
    }

    @Override
    public void onCancel(UsbDevice device) {
        Log.w(TAG, "onCancel");
    }
});
```

## Step2

权限申请通过之后，会进入到Step1中`USBMonitor`类的`onConnect`回调，经过设备过滤和判断之后，会执行`openUVCCamera`函数来开启UVC并初始化：

```java
...
// uvc开启
uvcCamera.openUVCCamera(ctrlBlock, DEFAULT_PREVIEW_MIN_FPS, DEFAULT_PREVIEW_MAX_FPS);
...

...
ConcreateUVCBuilder concreateUVCBuilder = new ConcreateUVCBuilder();
uvcCamera = concreateUVCBuilder
        .setUVCType(UVCType.xxx)
        .setOutputWidth(cameraWidth)
        .setOutputHeight(cameraHeight)
        .build();
// IRCMD init
ConcreteIRCMDBuilder concreteIRCMDBuilder = new ConcreteIRCMDBuilder();
ircmd = concreteIRCMDBuilder
        .setIrcmdType(IRCMDType.xxx)
        .setIdCamera(uvcCamera.getNativePtr())
        .build();

...
```

执行`startPreview`函数来出图：

```java
...
uvcCamera.setOpenStatus(true);
uvcCamera.setFrameCallback(iFrameCallback);
...
uvcCamera.onStartPreview();
...
```

## Step3

出图之后，会进入到`IFrameCallback`回调函数`onFrame`中，该函数会返回机芯中的红外和温度数据：

```java
iFrameCallback = new IFrameCallback() {
    @Override
    public void onFrame(byte[] frame) {
    	// 处理红外和温度数据
    	...
    	}
    }
```

## Step4

在文件`ImageThread.java`中把Step3中的红外数据进行格式转换：

```java
...
// yuv422格式转为ARGB格式
if (pseudocolorMode != null) {
    LibIRProcess.convertYuyvMapToARGBPseudocolor(imagesrc, (long) imageHeight * imageWidth, pseudocolorMode, imageARGB);
} else {
    LibIRParse.converyArrayYuv422ToARGB(imagesrc, imageHeight * imageWidth, imageARGB);
}
...
```

和旋转翻转：

```java
...
if (rotate) {
    LibIRProcess.ImageRes_t imageRes = new LibIRProcess.ImageRes_t();
    imageRes.height = (char) imageWidth;
    imageRes.width = (char) imageHeight;
    LibIRProcess.rotateRight90(imageARGB, imageRes, CommonParams.IRPROCSRCFMTType.IRPROC_SRC_FMT_ARGB8888, imageDst);
} else {
    imageDst = imageARGB;
}
...
```

最后转为bitmap:

```java
...
bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageDst));
...
```

## Step5

在需要展示红外画面或温度的地方引入控件：

```xml
<!-- 红外出图图层 -->
<com.infisense.usbir.view.CameraView
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

<!-- 温度图层 -->
<com.infisense.usbir.view.TemperatureView
    android:id="@+id/temperatureView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

在`CameraView.java`中，线程中对图像进行放大到适应屏幕，然后使用Canvas绘制：

```java
/**
 * 图片缩放，这里简单的使用getWidth()作为宽，getHeight()作为高，可能会出现画面拉伸情况，
 * 实际使用的时候请参考设备的宽高按照设备的图像尺寸做等比例缩放
 */
Bitmap mScaledBitmap = Bitmap.createScaledBitmap(bitmap, getWidth(), getHeight(), true);
canvas.drawBitmap(mScaledBitmap, 0, 0, null);

...
```

在`TemperatureView.java`中传递数据：

```java
...

// 用来关联温度数据和TemperatureView,方便后面的点线框测温
irtemp.setTempData(temperature);

...
```

# 注意事项

## USB Hub操作

Usbcontorl和Usbjni这两个类以及他们所在的文件夹必须原封不同的复制到您的项目中，不能修改包名

<img src="..\Common_Source\img\20211013184136.png" style="zoom:80%;" />

## USB设备插拔监听

AndroidManifest中监听USB设备的插拔，需要添加

```xml
        <activity
            ... >

            <!-- 监听USB设备的插拔 -->
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>

            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
```

# 使用说明

## 电脑无线连接手机

为了方便调试，使用电脑无线连接手机，首先需要配置adb环境，之后连接手机，步骤如下：

### adb环境配置

若已经配置好可跳过该步骤，具体配置网上有大量参考文章，这里不做过多描述。

### 命令行连接手机

可以使用命令行的方式连接手机，步骤如下：

首先请确认在手机的设置中打开了<开发者选项>,<无线调试>,<USB安装>选项，后续步骤如下：

- 手机跟电脑连接同一个网络下;

- 使用usb线连接手机，并检查是否连接成功

  ```
  C:\Users\zhao_>adb devices
  List of devices attached
  nvmvsct46hor4xts        device
  ```

- 打开手机端口： adb tcpip 5555 （默认是5555端口，可自己修改）

  ```
  C:\Users\zhao_>adb tcpip 5555
  restarting in TCP mode port: 5555
  ```

- 查看手机ip地址：adb shell ip -f inet addr show wlan0

```csharp
C:\Users\zhao_>adb shell ip -f inet addr show wlan0
32: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 3000
    inet 192.168.2.21/24 brd 192.168.2.255 scope global wlan0
       valid_lft forever preferred_lft forever
```

- 连接设备：adb connect 192.xxx.xxx.xxx:5555 (前面打印的手机ip地址)

```
C:\Users\zhao_>adb connect 192.168.2.21:5555
connected to 192.168.2.21:5555

C:\Users\zhao_>adb devices
List of devices attached
nvmvsct46hor4xts        device
192.168.2.21:5555       device
```

- 拔掉usb线，并查看无线是否连接成功

```ruby
C:\Users\zhao_>adb devices
List of devices attached
192.168.2.21:5555       device
```

### AndroidStudio插件方式连接手机

使用命令行的方式连接手机相对繁琐，用户可以选择使用AndroidStudio插件来简化该步骤，如下：

- 首先进入AndroidStudio设置里面的插件中，搜索adb wifi，下载一个下载量比较高的对应的插件。

  <img src="..\Common_Source\img\202010181317298.png" style="zoom:60%;" />

- 然后用usb连接电脑。

- 使用wifi插件点击connect

- 拔掉usb，即可用wifi进行调试。

## targetSdk版本设置

targetSdk<28时可以正常的申请和获取USB设备权限

targetSdk>=28时需要授权`Manifest.permission.CAMERA`才可以获取USB设备权限

### 原因分析

USBMonitor的回调函数onAttach中，请求权限的方法如下：

```java
mUSBMonitor.requestPermission(device);
```

继续追踪

```
this.mUsbManager.requestPermission(device, this.mPermissionIntent);
```

继续追踪

```java
/**
...
Permission for USB devices of class UsbConstants.USB_CLASS_VIDEO for clients that target SDK Build.VERSION_CODES.P and above can be granted only if they have additionally the Manifest.permission.CAMERA permission.
...
**/
public void requestPermission(UsbDevice device, PendingIntent pi) 
```

## 生成只包含指定ABI的apk

SDK中提供的默认架构为：`arm64-v8a, armeabi-v7a, x86, x86_64`

```css
从NDK R17开始只支持`arm64-v8a, armeabi-v7a, x86, x86_64`这四种架构的so库：
ABIs [armeabi] are not supported for platform. Supported ABIs are [arm64-v8a, armeabi-v7a, x86, x86_64]
```

如果想生成只包含指定ABI的apk，可以在app的build.gradle中配置如下：

```groovy
android {
    ...
    defaultConfig {
        ...
        // 生成包含指定平台的so库的apk
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'
        }
        ...
    }
    ...
}
```

## 代码混淆

如果使用代码混淆，请在您的混淆文件中添加如下内容：

```groovy
-keep class com.infisense.iruvc.** { *; }
```

具体的使用，请参考SDK demo中的混淆配置。

# 问题解析

## 插入模组无反应或出图不稳定

### 问题描述

插入sensor之后，设备没有任何反应，或出图不稳定，一段时间之后无法正常出图，或出现绿屏，花屏现象。

### 解决方法

- 请先确认设备的OTG是否打开（在设置中搜索，部分设备的OTG默认是打开的且无法进行关闭操作，部分设备的OTG打开一段时间之后不用的话会自动关闭）
- 可以对比第三方的出图工具如“USB摄像头”来进行对比测试
- 部分设备出图不稳定需要调用`uvcCamera.setDefaultBandwidth`方法来设置带宽
- 检查您的模组设置的分辨率是否正确
- 部分手机须通过转接器连接模组，如果电缆过长，超过0.5米以上，有可能出现供电不足，导致数据传输不稳定，需要在转接器中间再加个USB集线器，增加供电，保证信号传输稳定。

## 部分手机上出图一段时间后画面卡顿

### 问题描述

部分手机如荣耀Play5T，荣耀畅玩20等手机，插入模组后出图一段时间，从模组返回的数据长度会发生变化，例如正常256*192分辨率的模组，

```java
 @Override
public void onFrame(byte[] frame)
```

该回调函数中返回的数据长度为196609，但过一段时间后返回的数据长度变为196621或其它值，此时在截取红外和温度数据的时候，需要按照固定的数据长度去截取，不能按照返回数据长度的1/2去截取，否则会造成数据长度错误而导致的画面卡顿问题。

### 解决办法

解决方法为按照固定的数据长度去截取，如下：

```java
				private int imageOrTempDataLength = 256 * 192 * 2; // 红外或温度的数据长度

				...
                    
                /**
                 * 图像+温度
                 */
                /**
                 * copy红外数据到image数组中
                 * 出图的frame数组中前半部分是红外数据，后半部分是温度数据，
                 * 例如256*384分辨率的设备，前面的256*192是红外数据，后面的256*192是温度数据，
                 * 其中的数据是旋转90度的，需要旋转回来。
                 */
                System.arraycopy(frame, 0, imageSrc, 0, imageOrTempDataLength);
                System.arraycopy(frame, imageOrTempDataLength, temperatureSrc, 0, imageOrTempDataLength);
```

## 画面黑屏不出图

### 问题描述

插入sensor之后，画面没有任何反应，一直黑屏，不能正常出图。

### 解决方法

- 请先进入"测试`USBMonitor`的简单连接"这个页面，进行设备的插拔，查看是否进入`USBMonitor`对应的回调

- 如果没有进入回调，请检查您的`USBMonitor`注册是否执行

- 如果进入了回调，请跟踪调试是否请求了权限，位置：`IRUVC.java`文件中`mUSBMonitor.requestPermission(device);`，

  是否进入到了`IRUVC.java`文件中IFrameCallback的回调`public void onFrame(byte[] frame)`

  是否进入到了`CameraView.java`的绘制线程

- 检查您是否在`USBMonitor`的回调中设置了设备过滤白名单

- 在targetSDK>=30的设备上，可能需要获取到`android.permission.CAMERA`权限之后UVC才能申请出图成功，否则会出现`mUSBMonitor.requestPermission(device);`申请权限之后，执行`onConnect`回调，然后迅速执行`onCancel`回调的现象。

### 问题描述

Tiny1BE模组转接后插入设备，无法出图。

### 解决方法

经过多次转接之后可能会出现供电不足的情况，需要连接USB HUB加强供电，即可解决。

## targetSdk版本>=28，在部分的android10手机上不出图

targetSdk版本>=28，在部分的android10手机上，插入sensor设备的时候，在USBMonitor的回调函数onAttach里面回去申请权限，结果无法弹出申请权限的弹框，也无法出图。

系统一直打印这个信息 

```java
UsbUserSettingsManager: Camera permission required for USB video class devices
```

这个帖子有介绍，https://blog.csdn.net/wangchao1412/article/details/102837371，是系统层的问题并且没有更新补丁。

https://github.com/saki4510t/UVCCamera/issues/535 也提到即使授权Camera 也没用，系统代码里面判断`mUserContext.checkCallingPermission(android.Manifest.permission.CAMERA）`永远不成功。

### 问题验证

经测试 ，该问题只在 android10版本的手机上会出现，并且，三星和LG的android10手机可以正常出图，OPPO，小米，1+的android10手机无法正常出图。

对比FLIR的设备，在红米9A(android10)设备上安装FLIR ONE这个app，插入FILR的设备，也无法正常的出图。

### 解决建议

- 第一种：设置targetSdk<28

  可以正常的出图

- 第二种：有某些特殊的需求，要求targetSdk>=28

  可以正常的使用targetSdk>=28上架，然后在应用中判断用户使用的设备版本，如果是android10的设备，则通过打补丁或热修复或应用内升级的方式，修改targetSdk<28

  该种方案在Google Play Store上架的时候，可能会存在导致应用被下架的风险，请谨慎使用。

- 第三种：区分国内和国外渠道，国内渠道使用targetSdk<28，国外渠道使用targetSdk>=28

  目前该问题只出现在国产手机的android10版本上，使用该方案可以部分的避免该问题。

## 画面上有蒙层

### 问题描述

任何画面一直有下面的圈出来的阴影，如图所示：

<img src="..\Common_Source\img\20211014145717.jpg" style="zoom: 25%;" />

### 解决方法

#### 方法1

可能是快门异常

可以对着不同的背景打快门，看看是不是快门片没动，观察问题是否解决。

#### 方法2

可能是测试的时候对着圈出来的图像的物体进行了锅盖标定。

请对着均匀的温度面重新进行锅盖标定(标定完毕之后请重新做测温的二次标定)。

## 录制视频出现条纹

### 问题描述

如图所示，在预览的时候画面正常，但是在录制视频之后，画面会出现条纹，如下：

<img src="..\Common_Source\img\20211117103504.png" style="zoom:50%;" />

### 解决方法

- 原始的bitmap的宽高反掉了

  检查原始bitmap的宽高，如果反掉了则调换一下。

- 由于原始的画面如640x480的图片，为了适应屏幕进行缩放，如放大为884x663，这个时候画面在SurfaceView或TextureView中绘制是正常的，但是在进行视频编码的时候，编码器会自动的对奇数宽或高进行加一或减一转化为偶数的宽高，此时就会出现如上所示的条纹。

  在把原始图像放大后，进行奇数宽高判断，如果为奇数，则加一或减一像素如884x664，然后再传递到视频编码器中。

## 找不到libusb3803_hub.so

### 问题描述

如图所示，在编译运行SDK demo的时候日志中报错，如下：

<img src="..\Common_Source\img\20211220143532.png" />

### 解决方法

`libusb3803_hub.so`为系统自带的so库，用于解决特定机型的问题，如遇找不到该so库的问题，可以注释掉代码中`Usbcontorl`类的对应调用。

## 探测器响应率偏高或偏低

### 问题描述

在高增益或低增益下，出现探测器响应率偏高或偏低。

响应率偏高：高温目标测出温度偏高，低温目标测出温度偏低；响应率偏低与之相反。

### 解决方法

响应偏高或者偏低目前没有软件调节的方法，非常严重的那种可以找技术支持或者品质反馈，判定确实是响应异常可以算作不良品的。

目前SDK及I2C指令都没有支持探测器配置的修改，而且模组阶段如果改了响应，那所有的图像参数及测温标定都会失效，需要重标。

## 自动快门相关

### 问题描述

- 如何设置自动快门的开关及时间间隔？各个参数的含义？

### 解决方法

请参考`IRCMD`中的`setPropAutoShutterParameter`方法，具体参数及含义请在`doc`文件夹下`index.html`中查找

## 自动增益切换相关

### 问题描述

如何设置自动增益切换的开关和切换？各个参数的含义？

### 解决方法

自动增益切换适用于**具有高低增益的模组**，高增益和低增益的测温范围不同，当需要测温的物体不在当前增益的测温范围内时，开启该功能后会根据设置的参数判断是否满足切换的条件，满足的话会自动切换模组的增益状态，并回调。

**备注：部分单高增益的模组不适用。**

参数及调用方法如下：

```java
...
private boolean auto_gain_switch = true;
private LibIRProcess.AutoGainSwitchInfo_t auto_gain_switch_info = new LibIRProcess.AutoGainSwitchInfo_t();
private LibIRProcess.GainSwitchParam_t gain_switch_param = new LibIRProcess.GainSwitchParam_t();

...

// 自动增益切换参数auto gain switch parameter
gain_switch_param.above_pixel_prop = 0.1f;    //用于high -> low gain,设备像素总面积的百分比
gain_switch_param.above_temp_data = (int) ((130 + 273.15) * 16 * 4); //用于high -> low gain,高增益向低增益切换的触发温度
gain_switch_param.below_pixel_prop = 0.95f;   //用于low -> high gain,设备像素总面积的百分比
gain_switch_param.below_temp_data = (int) ((110 + 273.15) * 16 * 4);//用于low -> high gain,低增益向高增益切换的触发温度
auto_gain_switch_info.switch_frame_cnt = 5 * 15; //连续满足触发条件帧数超过该阈值会触发自动增益切换(假设出图速度为15帧每秒，则5 * 15大概为5秒)
auto_gain_switch_info.waiting_frame_cnt = 7 * 15;//触发自动增益切换之后，会间隔该阈值的帧数不进行增益切换监测(假设出图速度为15帧每秒，则7 * 15大概为7秒)

...

// 自动增益切换，不生效的话请您的设备是否支持自动增益切换
if (auto_gain_switch) {
    ircmd.autoGainSwitch(temperatureSrc, imageRes, auto_gain_switch_info,
            gain_switch_param, new IRCMD.AutoGainSwitchCallback() {
                @Override
                public void onAutoGainSwitchState(CommonParams.PropTPDParamsValue.GAINSELStatus gainselStatus) {

                }

                @Override
                public void onAutoGainSwitchResult(CommonParams.PropTPDParamsValue.GAINSELStatus gainselStatus, int result) {

                }
            });
}
```

## 测温修正相关

### 问题描述

测温修正接口如何使用？参数含义及如何设置？各参数的单位？

### 解决方法

测温修正的详细使用见 `用户开发标定 User calibration instructions->环境变量修正Ambient variable correction->环境变量修正Ambient variable correction.pdf`

## 锅盖标定相关

### 问题描述

如何进行锅盖标定，标定流程是什么？重新标定锅盖是否需要重置之前的？

### 解决方法

锅盖标定的具体流程，见文档 `用户开发标定 User calibration instructions->测温与锅盖标定Secondary calibration& Lid pattern noise correction->锅盖标定Lid pattern noise correction.pdf`

## 最高温，最低温以及中心点温度相关

### 问题描述

如何获取最高温，最低温和中心点温度？

### 解决方法

获取温度有两种方式，如下：

#### 方式一：从机芯返回的温度数据中，获取温度信息

使用到LibIRTemp类，使用方式见`TemperatureView.java`中

具体如下：

```java
...
private LibIRTemp irtemp;
...
    
irtemp = new LibIRTemp(imageWidth, imageHeight);  
...
    
// 用来关联温度数据
irtemp.setTempData(temperature);
...
```

使用到的具体函数如下：

```java
/**
 * 设置温度数据【copy Temperature data from buffer】<br/>
 *
 * @param src Temperature buffer
 */
public void setTempData(byte[] src)

	/**
     * 获取线的温度（包括最大值，最小值及坐标，平均值）【Get the temperature of the line (including maximum, minimum and coordinates, average)】<br/>
     * (units:Celsius)
     *
     * @param line Temperature coordinates
     * @return TemperatureSampleResult
     */
    public TemperatureSampleResult getTemperatureOfLine(Line line)
    
	/**
     * 获取框的温度（包括最大值，最小值及坐标，平均值）【Get the temperature of the frame (including maximum, minimum and coordinates, average)】<br/>
     * (units:Celsius)
     *
     * @param rect Rectangular area coordinates
     * @return TemperatureSampleResult
     */
    public TemperatureSampleResult getTemperatureOfRect(Rect rect)
```

#### 方式二：直接从机芯中获取温度信息

使用到IRCMD类，使用方式见`PopupCalibration.java`中

具体如下：

```java
/**
 * 获取点测温的温度信息【Get the point temperature information】<br/>
 * Please make sure the pointX and PointY is the sensor's real point<br/>
 *
 * @param pixelPointX      The point pixel's x location
 * @param pixelPointY      The point pixel's y location
 * @param temperatureValue length:1 units:Kelvin
 * @return see {@link IrcmdResult}
 */
public int getPointTemperatureInfo(int pixelPointX, int pixelPointY, int[] temperatureValue)

	/**
     * 获取线测温的温度信息（包括最大值，最小值及坐标，平均值）
     * 【Get the line temperature information(including maximum, minimum and coordinates, average)】<br/>
     * Please make sure the pointX and PointY is the sensor's real point<br/>
     *
     * @param startPointX      框的左上角x坐标
     * @param startPointY      框的左上角y坐标
     * @param endPointX        框的右下角x坐标
     * @param endPointY        框的右下角y坐标
     * @param temperatureValue length:7<br/>
     *                         temperatureValue[0]:ave_temp; units:Kelvin<br/>
     *                         temperatureValue[1]:max_temp; units:Kelvin<br/>
     *                         temperatureValue[2]:min_temp; units:Kelvin<br/>
     *                         temperatureValue[3]:max_temp_point.x;<br/>
     *                         temperatureValue[4]:max_temp_point.y;<br/>
     *                         temperatureValue[5]:min_temp_point.x;<br/>
     *                         temperatureValue[6]:min_temp_point.y;<br/>
     * @return see {@link IrcmdResult}
     */
    public int getLineTemperatureInfo(int startPointX, int startPointY, int endPointX, int endPointY, int[] temperatureValue)

	/**
     * 获取框测温的温度信息（包括最大值，最小值及坐标，平均值）
     * 【Get the rectangle temperature information(including maximum, minimum and coordinates, average)】<br/>
     * Please make sure the pointX and PointY is the sensor's real point<br/>
     *
     * @param startPointX      框的左上角x坐标
     * @param startPointY      框的左上角y坐标
     * @param endPointX        框的右下角x坐标
     * @param endPointY        框的右下角y坐标
     * @param temperatureValue length:7<br/>
     *                         temperatureValue[0]:ave_temp; units:Kelvin<br/>
     *                         temperatureValue[1]:max_temp; units:Kelvin<br/>
     *                         temperatureValue[2]:min_temp; units:Kelvin<br/>
     *                         temperatureValue[3]:max_temp_point.x;<br/>
     *                         temperatureValue[4]:max_temp_point.y;<br/>
     *                         temperatureValue[5]:min_temp_point.x;<br/>
     *                         temperatureValue[6]:min_temp_point.y;<br/>
     * @return see {@link IrcmdResult}
     */
    public int getRectTemperatureInfo(int startPointX, int startPointY, int endPointX, int endPointY,
                                      int[] temperatureValue)
    
	/**
     * 获取整帧的最大最小温度信息【Get the maximum and minimum temperature information of the frame】<br/>
     *
     * @param temperatureValue length:6<br/>
     *                         temperatureValue[0]:max_temp; units:Kelvin<br/>
     *                         temperatureValue[1]:min_temp; units:Kelvin<br/>
     *                         temperatureValue[2]:max_temp_point.x;<br/>
     *                         temperatureValue[3]:max_temp_point.y;<br/>
     *                         temperatureValue[4]:min_temp_point.x;<br/>
     *                         temperatureValue[5]:min_temp_point.y;<br/>
     * @return see {@link IrcmdResult}
     */
    public int getCurrentFrameMaxAndMinTemperature(int[] temperatureValue)
```

## 模组的伪彩出现反色

### 问题描述

在调用`saveSpiConfig`接口保存配置信息的时候，模组突然拔掉或断电，重新上电之后，模组出现伪彩反色的情况。

### 解决方法

出现该问题是因为CMD的命令为耗时操作，在保存数据的过程中突然断电会导致Flash数据混乱。所以在发送cmd命令的时候，需要等待命令执行完之后再进行其它的操作。

出现该问题后，可以通过调用`restoreDefaultConfig`方法的`DEF_CFG_ALL`参数来恢复。

## 部分手机上出现SurfaceView内容不展示问题

**说明：该部分业务逻辑非本SDK的使用相关，提供该问题示例为方便客户开发自己的上层应用。**

### 问题描述

部分手机如小米mix2上会出现`SurefaceView`内容如点线框画完之后不展示的问题，该问题是第三方系统厂商优化定制系统的原因，与SDK无关。

### 解决方法

在`SurfaceView`的构造函数中添加如下代码：

```java
// 注意这个方法尽早执行(可以在构造方法里面执行)
setZOrderOnTop(true);
```

## 部分三星手机录制视频相册找不到

**说明：该部分业务逻辑非本SDK的使用相关，提供该问题示例为方便客户开发自己的上层应用。**

### 问题描述

部分三星手机录制视频后提示保存成功，但是打开相册找不到保存的视频，但是手机文件系统可找到保存的视频。

### 问题分析

手机文件系统可找到保存的视频说明保存这一步没有问题，应该是刷新相册系统失败导致相册找不到文件。

### 解决方法

- 对三星手机延时发送刷新相册广播

  ```java
   Message message = new Message();
          message.what = MESSAGE_WHAT_ALBUM_UPDATE;
          message.obj = MediaPath;
          //照片已经直接刷新相册，视频根据是否是三星手机处理
          if (MediaPath.endsWith(".mp4") || MediaPath.endsWith(".MP4")) {
              //三星手机延时刷新系统文件夹
              if (Util.isSamsung()) {
                  mHandler.sendMessageDelayed(message, 1000);
              } else {
                  AppUtil.showCenterToast(com.infisense.baselibrary.R.string.video_save_successfully);
                  mHandler.sendMessageDelayed(message, 0);
              }
          }
  ```

- 对三星手机使用多种刷新方式

  ```java
    // 刷新相册 方式1 
     MediaScannerConnection.scanFile(Utils.getApp(), new String[]{path}, null, null);
      //三星手机特殊处理
      if (Util.isSamsung()) {
         //三星Note8生效 方式2 
         ContentValues values = new ContentValues();
         values.put(MediaStore.Video.Media.DATA, path);
                      getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        //方式3 
       Intent intent = new Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE");
                      intent.setData(Uri.fromFile(new File(path)));
                      getActivity().sendBroadcast(intent);
      }
  ```

# 版本【Versions】

## 版本【Version】1.3.7 

- 修复一些bug；
- 区分不同的版本；

## 版本【Version】1.3.6 

- 调整页面逻辑，更加简洁高效；
- 更新libirtemp库到0.15.1 alpha版本；

## 版本【Version】1.3.5 2023-04-23

- 放开距离修正和环境变量修正的接口限制，使用固件版本作为唯一区分；
- 更新单点标定和两点标定的文档及注释;
- 更新libirtemp库到0.15.0_alpha版本;

## 版本【Version】1.3.4 

- 抽取测温修正函数到`IRUtils.java`工具类中，方便在不插入设备的情况下进行测温修正；
- 调整测温修正的逻辑，如果是`P2`模组则只需要执行`step2`，添加使用说明；


## 版本【Version】1.3.3 

- 增加DOKIT工具库，用于监测运行数据；
- 更新libirtemp库到最新版本；
- 更新temperatureCorrection方法实现；
- 调整伪彩的提供方式，建议使用机芯伪彩或自定义伪彩；

## 版本【Version】1.3.2 FC  2023-02-13

- 新增手动制造盲元接口addPseudoDeadPixel和相关文档；
- 新增自动盲元标定接口 ；
- 完善新增盲元表和移除盲元表接口addDeadPixelPoint，removeDeadPixelPoint；
- 完善温度修正接口temperatureCorrection，兼容Tiny1c Tinybe MINI256；
- 中间出图问题修复；
- 新增伪彩获取接口，允许传入自定义伪彩列表；
- 添加自定义等温尺功能；
- 完善P2，Tiny1C，TinyBE，MINI640/384的PN,SN规则；
- 解决固件升级失败后无法再次升级的问题;

## 版本【Version】1.3.1

- 完善多点标定接口；
- 新增多点标定后单点温度修正接口；
- 更新libirtemp 0.13.1 alpha库；
- 修复在部分android9.0手机上出现的surfaceView内容不展示的bug;

## 版本【Version】1.3.0

- 通过gradle.properties区分标准版本和定制版本；
- 新增标准版和定制版与固件匹配出图逻辑；
- 新增定制版接口`getCustomInfo`获取PID和VID；
- 增加AES-CBC-PKCS5Padding的加密和解密工具类；

## 版本【Version】1.2.9  

- 添加模组出现伪彩反色的问题解析和解决办法；
- 调整算法中电子变倍的位置到最后；
- 删除定制客户的特殊设备使用的供电命令；
- 修改demo的targetSDK为26；
- 删除demo中的第三方库依赖；
- 添加SDK的混淆，在demo中配置混淆依赖；
- 添加demo中模组插拔的监听逻辑；
- 添加`LibIRProcess`库中`yuv422`数据获取伪彩的方法；
- 增加获取机芯中是否执行过停图命令的接口；
- 更新libirtemp库到最新分支；

## 版本【Version】1.2.8 FC  2022-11-04

- 多点标定SDK接口逻辑调整，增加参数，详见文档部分；
- 删除部分接口需要传入固件信息的参数，改为从底层获取；
- 简化demo中部分代码的执行逻辑，
- 修复了存在的一些bug；

## 版本【Version】1.2.7 FC  2022-11-01

- 增加多点标定的SDK，具体的使用方法见文档部分；
- 修改了SDK初始化的逻辑，先获取到设备分辨率列表再初始化SDK；
- 修改了部分接口的参数；
- 修复了存在的一些bug；

## 版本【Version】1.2.6  2022-10-18

- 优化demo布局，移除demo第三方库以及无用的代码

- SDK新增SPI单光支持上层ISP算法功能以及演示demo

- IRCMD支持`readPrivData`、`readKTData`、`readBTData`接口调用

- UVCCamera支持`setCurVTemp`、`setTempCorrectParams`、`setEnvCorrectParams`、`initIRISPModule`、`setAGCStatus`、

  `setDDEStatus`、`setDenoiseStatus`接口调用

## 版本【Version】1.2.3

- 固件版本>=3.05中，去除了机芯中设置环境变量的接口(`setPropTPDParams`接口中的`CommonParams.PropTPDParams#TPD_PROP_DISTANCE`参数)，接口中添加固件版本信息`project_info`参数用以区分；

## 版本【Version】1.2.1

- LEVEL_DDE的档位仅支持0-4这5个档位，删除5和6两个档位
- 修改环境变量参数修正的输入温度单位为摄氏度
- 更新Libirparse类名为LibIRParse，更新Libirprocess类名为LibIRProcess，更新Libirtemp类名为LibIRTemp；
- 对外提供统一的IRCMD类供调用，使用新的方法初始化IRCMD;
- 修改LibIRParse，LibIRProcess，LibIRTemp，IRCMD中的方法名以符合命名规范；
- 添加数据流模式切换示例demo
- 调整测温二次修正算法

版本【Version】1.2.0  2021-10-22
------------------------------------------

- 增加SDK实现【Increase SDK implementation】
- 更新接口文档【Update interface documentation】
- 更新用户开发标定文档【Update user development calibration documents】
- 增加兼容性测试【Increase compatibility test】

版本【Version】1.1.2  2021-07-22
------------------------------------------

- 增加SDK实现【Increase SDK implementation】
- 自动增益切换【Automatic gain switching】
- 防灼烧保护【Anti-burn protection】
- 环境温度修正【Ambient temperature correction】
- firmware 升级【firmware upgrade】
- 升级libircmd的库【Upgrade libircmd library】

版本【Version】1.0.5  2021-06-08
------------------------------------------

- release I2c cmd
- Release spi 接口【Release spi interface】
- Tiny1C模组SDK接口【Tiny1C module SDK interface】

- release USB 接口【release USB interface】
