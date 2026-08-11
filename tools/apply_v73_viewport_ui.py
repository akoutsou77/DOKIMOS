from pathlib import Path


def replace_once(text, old, new, label):
    if old not in text:
        raise SystemExit(f"missing patch anchor: {label}")
    return text.replace(old, new, 1)

main_path = Path('synccam/app/src/main/java/com/openai/synccam/MainActivity.java')
cam_path = Path('synccam/app/src/main/java/com/openai/synccam/Camera2Controller.java')
gradle_path = Path('synccam/app/build.gradle')
aspect_path = Path('synccam/app/src/main/java/com/openai/synccam/AspectTextureView.java')

main = main_path.read_text(encoding='utf-8')
main = replace_once(
    main,
    '    private TextureView surface;\n    private TextView status, sync, peers, roleChip, groupChip, countdownBadge, trigger;',
    '    private AspectTextureView surface;\n    private TextView status, sync, peers, roleChip, groupChip, countdownBadge, trigger;\n    private View shadeOverlay, reticleOverlay, topUi, deckUi;\n    private TextView uiToggleButton;\n    private boolean uiHidden = false;',
    'MainActivity fields')

main = replace_once(
    main,
    '        surface = new TextureView(this);\n        surface.setSurfaceTextureListener(this);\n        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));',
    '        surface = new AspectTextureView(this);\n        surface.setSurfaceTextureListener(this);\n        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));',
    'AspectTextureView creation')

main = replace_once(
    main,
    '        View shade = new View(this);\n        GradientDrawable shadeBg = new GradientDrawable(\n                GradientDrawable.Orientation.TOP_BOTTOM,\n                new int[]{0xC9000000, 0x18000000, 0x00000000, 0x30000000, 0xC9000000});\n        shade.setBackground(shadeBg);\n        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));\n\n        ReticleView reticle = new ReticleView(this);\n        root.addView(reticle, new FrameLayout.LayoutParams(-1, -1));',
    '        shadeOverlay = new View(this);\n        GradientDrawable shadeBg = new GradientDrawable(\n                GradientDrawable.Orientation.TOP_BOTTOM,\n                new int[]{0xC9000000, 0x18000000, 0x00000000, 0x30000000, 0xC9000000});\n        shadeOverlay.setBackground(shadeBg);\n        root.addView(shadeOverlay, new FrameLayout.LayoutParams(-1, -1));\n\n        reticleOverlay = new ReticleView(this);\n        root.addView(reticleOverlay, new FrameLayout.LayoutParams(-1, -1));',
    'overlay fields')

main = replace_once(
    main,
    '        LinearLayout top = new LinearLayout(this);\n        top.setOrientation(LinearLayout.VERTICAL);',
    '        LinearLayout top = new LinearLayout(this);\n        topUi = top;\n        top.setOrientation(LinearLayout.VERTICAL);',
    'top UI field')

main = replace_once(
    main,
    '        LinearLayout deck = new LinearLayout(this);\n        deck.setOrientation(LinearLayout.VERTICAL);',
    '        LinearLayout deck = new LinearLayout(this);\n        deckUi = deck;\n        deck.setOrientation(LinearLayout.VERTICAL);',
    'deck UI field')

main = replace_once(
    main,
    '        flashOverlay.setClickable(false);\n        root.addView(flashOverlay, new FrameLayout.LayoutParams(-1, -1));\n\n        setContentView(root);',
    '        flashOverlay.setClickable(false);\n        root.addView(flashOverlay, new FrameLayout.LayoutParams(-1, -1));\n\n        uiToggleButton = smallButton("HIDE UI");\n        uiToggleButton.setAlpha(0.82f);\n        FrameLayout.LayoutParams toggleLp = new FrameLayout.LayoutParams(dp(72), dp(38), Gravity.TOP | Gravity.END);\n        toggleLp.topMargin = dp(8);\n        toggleLp.rightMargin = dp(8);\n        root.addView(uiToggleButton, toggleLp);\n\n        setContentView(root);',
    'UI toggle button')

main = replace_once(
    main,
    '        cameraSettingsButton.setOnClickListener(v -> showCameraSettings());\n        copy.setOnClickListener(v -> copyConnectionInfo());',
    '        cameraSettingsButton.setOnClickListener(v -> showCameraSettings());\n        uiToggleButton.setOnClickListener(v -> toggleUiVisibility());\n        copy.setOnClickListener(v -> copyConnectionInfo());',
    'UI toggle listener')

main = replace_once(
    main,
    '    private LinearLayout metricCard(String title, String value) {',
    '''    private void toggleUiVisibility() {
        uiHidden = !uiHidden;
        int visibility = uiHidden ? View.GONE : View.VISIBLE;
        if (topUi != null) topUi.setVisibility(visibility);
        if (deckUi != null) deckUi.setVisibility(visibility);
        if (shadeOverlay != null) shadeOverlay.setVisibility(visibility);
        if (reticleOverlay != null) reticleOverlay.setVisibility(visibility);
        if (countdownBadge != null) {
            if (uiHidden) countdownBadge.setVisibility(View.GONE);
            else if (activeCountdownSeq >= 0) countdownBadge.setVisibility(View.VISIBLE);
        }
        if (uiToggleButton != null) {
            uiToggleButton.setText(uiHidden ? "SHOW UI" : "HIDE UI");
            uiToggleButton.setAlpha(uiHidden ? 0.55f : 0.82f);
        }
    }

    private LinearLayout metricCard(String title, String value) {''',
    'toggleUiVisibility method')

main_path.write_text(main, encoding='utf-8')

cam = cam_path.read_text(encoding='utf-8')
cam = replace_once(cam, '    private final TextureView textureView;', '    private final AspectTextureView textureView;', 'controller view field')
cam = replace_once(cam, '    Camera2Controller(Activity activity, TextureView textureView, Listener listener) {', '    Camera2Controller(Activity activity, AspectTextureView textureView, Listener listener) {', 'controller constructor')
cam = replace_once(
    cam,
    '        previewSize = choosePreviewSize(previewChoices, textureView.getWidth(), textureView.getHeight());\n        if (settings.pictureSize == null || previewSize == null) {',
    '        previewSize = choosePreviewSize(previewChoices, settings.pictureSize, textureView.getWidth(), textureView.getHeight());\n        if (settings.pictureSize == null || previewSize == null) {',
    'preview size call')
cam = replace_once(
    cam,
    '            listener.onError("Camera does not expose compatible preview/JPEG sizes");\n            return;\n        }\n        SurfaceTexture texture = textureView.getSurfaceTexture();',
    '            listener.onError("Camera does not expose compatible preview/JPEG sizes");\n            return;\n        }\n        updateViewportAspect(c, previewSize);\n        SurfaceTexture texture = textureView.getSurfaceTexture();',
    'viewport aspect call')
cam = replace_once(
    cam,
    '                float scale = Math.max((float) viewHeight / size.getHeight(), (float) viewWidth / size.getWidth());',
    '                float scale = Math.min((float) viewHeight / size.getHeight(), (float) viewWidth / size.getWidth());',
    'aspect-fit transform')

old_choose = '''    private static Size choosePreviewSize(Size[] sizes, int viewW, int viewH) {
        if (sizes == null || sizes.length == 0) return null;
        ArrayList<Size> candidates = new ArrayList<>(Arrays.asList(sizes));
        candidates.sort(Comparator.comparingLong(Camera2Controller::area));
        Size best = candidates.get(0);
        long target = Math.max(1, (long) Math.max(viewW, viewH) * Math.max(viewW, viewH));
        for (Size s : candidates) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1920) best = s;
            if (area(s) >= target && s.getWidth() <= 1920 && s.getHeight() <= 1920) break;
        }
        return best;
    }'''
new_choose = '''    private static Size choosePreviewSize(Size[] sizes, Size pictureSize, int viewW, int viewH) {
        if (sizes == null || sizes.length == 0) return null;
        double targetRatio;
        if (pictureSize != null && pictureSize.getHeight() > 0) {
            targetRatio = (double) pictureSize.getWidth() / pictureSize.getHeight();
        } else {
            int shortSide = Math.max(1, Math.min(viewW, viewH));
            int longSide = Math.max(shortSide, Math.max(viewW, viewH));
            targetRatio = (double) longSide / shortSide;
        }

        ArrayList<Size> bounded = new ArrayList<>();
        for (Size s : sizes) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1920) bounded.add(s);
        }
        if (bounded.isEmpty()) bounded.addAll(Arrays.asList(sizes));

        bounded.sort((a, b) -> {
            double da = Math.abs(((double) a.getWidth() / a.getHeight()) - targetRatio);
            double db = Math.abs(((double) b.getWidth() / b.getHeight()) - targetRatio);
            int ratioOrder = Double.compare(da, db);
            if (ratioOrder != 0) return ratioOrder;
            return Long.compare(area(b), area(a));
        });
        return bounded.get(0);
    }'''
cam = replace_once(cam, old_choose, new_choose, 'preview chooser')

cam = replace_once(
    cam,
    '    private void configureTransform(LensInfo lens, Size size) {',
    '''    private void updateViewportAspect(CameraCharacteristics c, Size size) {
        if (c == null || size == null) return;
        Integer sensor = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensorDegrees = sensor == null ? 90 : sensor;
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int displayDegrees;
        if (rotation == Surface.ROTATION_90) displayDegrees = 90;
        else if (rotation == Surface.ROTATION_180) displayDegrees = 180;
        else if (rotation == Surface.ROTATION_270) displayDegrees = 270;
        else displayDegrees = 0;
        boolean swap = ((sensorDegrees - displayDegrees + 360) % 180) == 90;
        int ratioWidth = swap ? size.getHeight() : size.getWidth();
        int ratioHeight = swap ? size.getWidth() : size.getHeight();
        textureView.post(() -> textureView.setAspectRatio(ratioWidth, ratioHeight));
    }

    private void configureTransform(LensInfo lens, Size size) {''',
    'viewport aspect helper')

cam_path.write_text(cam, encoding='utf-8')

aspect_path.write_text('''package com.openai.synccam;

import android.content.Context;
import android.view.TextureView;
import android.view.View;

/** TextureView that fits the camera stream inside the available screen without distorting its aspect ratio. */
final class AspectTextureView extends TextureView {
    private int ratioWidth;
    private int ratioHeight;

    AspectTextureView(Context context) {
        super(context);
    }

    void setAspectRatio(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (ratioWidth == width && ratioHeight == height) return;
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int availableWidth = View.MeasureSpec.getSize(widthMeasureSpec);
        int availableHeight = View.MeasureSpec.getSize(heightMeasureSpec);
        if (ratioWidth == 0 || ratioHeight == 0 || availableWidth == 0 || availableHeight == 0) {
            setMeasuredDimension(availableWidth, availableHeight);
            return;
        }

        long heightAtFullWidth = (long) availableWidth * ratioHeight / ratioWidth;
        if (heightAtFullWidth <= availableHeight) {
            setMeasuredDimension(availableWidth, Math.max(1, (int) heightAtFullWidth));
        } else {
            int fittedWidth = (int) ((long) availableHeight * ratioWidth / ratioHeight);
            setMeasuredDimension(Math.max(1, fittedWidth), availableHeight);
        }
    }
}
''', encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = replace_once(gradle, '        versionCode 9\n        versionName \'7.2.0-lens-selection\'', '        versionCode 10\n        versionName \'7.3.0-native-viewport\'', 'version bump')
gradle_path.write_text(gradle, encoding='utf-8')

print('Applied SyncCam v7.3 hideable UI and native-aspect viewport patch')
