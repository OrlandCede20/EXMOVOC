package com.example.exmovoc;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.core.app.ActivityCompat;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;

import com.example.exmovoc.ArchivosCX.CameraConnectionFragment;
import com.example.exmovoc.ArchivosCX.ImageUtils;

import com.example.exmovoc.ml.LugaresUTEQ;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ImageReader.OnImageAvailableListener {

    TextView txtresul;
    ImageButton btnEscuchar;
    ImageButton btnCamera;

    TextToSpeech txtsp;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        txtresul = findViewById(R.id.result);
        btnEscuchar = findViewById(R.id.btnescuchar);
        btnCamera=findViewById(R.id.btncamara);

        txtsp = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int i) {
                if (i!=TextToSpeech.ERROR)
                    txtsp.setLanguage(new Locale("ES"));
            }
        });
        btnEscuchar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                String text = txtresul.getText().toString();
                txtsp.speak(text,TextToSpeech.QUEUE_FLUSH,null);
            }
        });
        btnCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED ) {
                    }else{
                        setFragment();
                    }
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                    {

                    }
                } else {

                }
            }
        });

       /* if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED ) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, 121);
            }else{
                setFragment();
            }
        } else {
            setFragment();
        }*/

    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull
    int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for(int i=0; i<permissions.length; i++){
            if(permissions[i].equals(android.Manifest.permission.CAMERA)){
                btnCamera.setEnabled(grantResults[i] == PackageManager.PERMISSION_GRANTED);
            } else if(permissions[i].equals(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE) ||
                    permissions[i].equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            ) {

            }
        }
        /*
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFragment();
        } else {
            finish();
        }*/
    }
    int previewHeight = 0,previewWidth = 0;
    int sensorOrientation;
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    protected void setFragment() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String cameraId = null;
        try {
            cameraId = manager.getCameraIdList()[0];
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        CameraConnectionFragment fragment;
        CameraConnectionFragment camera2Fragment =
                CameraConnectionFragment.newInstance(
                        new CameraConnectionFragment.ConnectionCallback() {
                            @Override
                            public void onPreviewSizeChosen(final Size size, final int rotation) {
                                previewHeight = size.getHeight(); previewWidth = size.getWidth();
                                sensorOrientation = rotation - getScreenOrientation();
                            }
                        },
                        this, R.layout.camera_fragment, new Size(640, 480));
        camera2Fragment.setCamera(cameraId);
        fragment = camera2Fragment;
        getFragmentManager().beginTransaction().replace(R.id.container, fragment).commit();
    }
    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;

            default:
                return 0;
        }
    }
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;
    private Runnable postInferenceCallback;
    private Runnable imageConverter;
    private Bitmap rgbFrameBitmap;

    @Override
    public void onImageAvailable(ImageReader reader) {
        if (previewWidth == 0 || previewHeight == 0) return;
        if (rgbBytes == null) rgbBytes = new int[previewWidth * previewHeight];
        try {
            final Image image = reader.acquireLatestImage();
            if (image == null) return;
            if (isProcessingFrame) { image.close(); return; }
            isProcessingFrame = true;
            final Image.Plane[] planes = image.getPlanes();
            fillBytes(planes, yuvBytes);
            yRowStride = planes[0].getRowStride();
            final int uvRowStride = planes[1].getRowStride();
            final int uvPixelStride = planes[1].getPixelStride();
            imageConverter = new Runnable() {
                @Override
                public void run() {
                    ImageUtils.convertYUV420ToARGB8888( yuvBytes[0], yuvBytes[1], yuvBytes[2], previewWidth, previewHeight,
                            yRowStride,uvRowStride, uvPixelStride,rgbBytes);
                }
            };
            postInferenceCallback = new Runnable() {
                @Override
                public void run() { image.close(); isProcessingFrame = false; }
            };
            processImage();
        } catch (final Exception e) { }
    }
    protected void fillBytes(final Image.Plane[] planes, final byte[][] yuvBytes) {
        for (int i = 0; i < planes.length; ++i) {
            final ByteBuffer buffer = planes[i].getBuffer();
            if (yuvBytes[i] == null) {
                yuvBytes[i] = new byte[buffer.capacity()];
            }
            buffer.get(yuvBytes[i]);
        }
    }
    int maxPos = 0;
    private void processImage() {

        imageConverter.run();
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888);
        rgbFrameBitmap.setPixels(rgbBytes, 0, previewWidth, 0, 0, previewWidth, previewHeight);
        try {
            LugaresUTEQ model = LugaresUTEQ.newInstance(this);
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);
            int imageSize = 224;
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3);
            byteBuffer.order(ByteOrder.nativeOrder());

            Bitmap ds = Bitmap.createScaledBitmap(rgbFrameBitmap,224,224,true);

            int[] intValues = new int[imageSize * imageSize];
            ds.getPixels(intValues, 0, ds.getWidth(), 0, 0, ds.getWidth(), ds.getHeight());
            int pixel = 0;
            for (int i = 0; i < imageSize; i++) {
                for (int j = 0; j < imageSize; j++) {
                    int val = intValues[pixel++]; // RGB
                    byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.0f / 255.0f));
                    byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.0f / 255.0f));
                    byteBuffer.putFloat((val & 0xFF) * (1.0f / 255.0f));
                }
            }


            inputFeature0.loadBuffer(byteBuffer);
            LugaresUTEQ.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] confidences = outputFeature0.getFloatArray();

            float maxConfidence = 0.0f;
            for (int i = 0; i < confidences.length; i++) {
                if (confidences[i] > maxConfidence) {
                    maxConfidence = confidences[i];
                    maxPos = i;
                }
            }
            String[] classes = {
                                    "Polideportivo", "Facultad de ciencias empresariales", "Facultad de Ciencias Sociales y Económicas", "Comedor",
                                    "Parqueadero", "Facultad de Salud","Rectorado","Departamento de archivos",
                                    "Facultad de Pedagogía","Centro Médico","Departamento de Investigación","Bibloteca","Bar",
                                    "Departamento Académico","Auditorio","Instituto de informática","Rotonda",

                                 };

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    txtresul.setText(classes[maxPos]+" "+confidences[maxPos]*100+"%");
                }
            });

            model.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        postInferenceCallback.run();
    }
}