package io.yapix.action;

import static io.yapix.base.util.NotificationUtils.notifyError;
import static io.yapix.base.util.NotificationUtils.notifyInfo;
import static java.lang.String.format;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AtomicDouble;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.util.PsiTreeUtil;
import io.yapix.base.StepResult;
import io.yapix.base.util.ConcurrentUtils;
import io.yapix.base.util.NotificationUtils;
import io.yapix.base.util.PsiFileUtils;
import io.yapix.config.DefaultConstants;
import io.yapix.config.YapixConfig;
import io.yapix.config.YapixConfigUtils;
import io.yapix.model.Api;
import io.yapix.parse.ApiParser;
import io.yapix.parse.model.ClassParseData;
import io.yapix.parse.model.MethodParseData;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jetbrains.annotations.NotNull;

/**
 * API????????????????????????????????????
 */
public abstract class AbstractAction extends AnAction {

    /**
     * ????????????????????????
     */
    private final boolean requiredConfigFile;

    protected AbstractAction() {
        this.requiredConfigFile = true;
    }

    protected AbstractAction(boolean requiredConfigFile) {
        this.requiredConfigFile = requiredConfigFile;
    }

    @Override
    public void actionPerformed(AnActionEvent event) {
        // make sure documents all saved before refresh v-files in sync/recursive.
        FileDocumentManager.getInstance().saveAllDocuments();
        EventData data = EventData.of(event);
        if (!data.shouldHandle()) {
            return;
        }
        // 1.????????????
        StepResult<YapixConfig> configResult = resolveConfig(data);
        YapixConfig config = configResult.getData();
        if (!configResult.isContinue()) {
            return;
        }
        // 2.????????????
        if (!before(event, config)) {
            return;
        }
        // 3.????????????
        StepResult<List<Api>> apisResult = parse(data, config);
        if (!apisResult.isContinue()) {
            return;
        }
        // 4.????????????
        List<Api> apis = apisResult.getData();
        handle(event, config, apis);
    }

    /**
     * ???????????????
     */
    public boolean before(AnActionEvent event, YapixConfig config) {
        return true;
    }

    /**
     * ????????????
     */
    public abstract void handle(AnActionEvent event, YapixConfig config, List<Api> apis);

    /**
     * ????????????????????????
     */
    private StepResult<List<Api>> parse(EventData data, YapixConfig config) {
        ApiParser parser = new ApiParser(data.project, data.module, config);
        // ????????????
        if (data.selectedMethod != null) {
            MethodParseData methodData = parser.parse(data.selectedMethod);
            if (!methodData.valid) {
                NotificationUtils.notifyWarning(DefaultConstants.NAME,
                        "The current method is not a valid api or ignored");
                return StepResult.stop();
            }
            if (config.isStrict() && StringUtils.isEmpty(methodData.declaredApiSummary)) {
                NotificationUtils.notifyWarning(DefaultConstants.NAME, "The current method must declare summary");
                return StepResult.stop();
            }
            return StepResult.ok(methodData.apis);
        }

        // ?????????
        if (data.selectedClass != null) {
            ClassParseData controllerData = parser.parse(data.selectedClass);
            if (!controllerData.valid) {
                NotificationUtils.notifyWarning(DefaultConstants.NAME,
                        "The current class is not a valid controller or ignored");
                return StepResult.stop();
            }
            if (config.isStrict() && StringUtils.isEmpty(controllerData.declaredCategory)) {
                NotificationUtils.notifyWarning(DefaultConstants.NAME, "The current class must declare category");
                return StepResult.stop();
            }
            return StepResult.ok(controllerData.getApis());
        }

        // ??????
        List<PsiClass> controllers = PsiFileUtils.getPsiClassByFile(data.selectedJavaFiles);
        if (controllers.isEmpty()) {
            NotificationUtils.notifyWarning(DefaultConstants.NAME, "Not found valid controller class");
            return StepResult.stop();
        }
        List<Api> apis = Lists.newLinkedList();
        for (PsiClass controller : controllers) {
            ClassParseData controllerData = parser.parse(controller);
            if (!controllerData.valid) {
                continue;
            }
            if (config.isStrict() && StringUtils.isEmpty(controllerData.declaredCategory)) {
                continue;
            }
            List<Api> controllerApis = controllerData.getApis();
            if (config.isStrict()) {
                controllerApis = controllerApis.stream().filter(o -> StringUtils.isNotEmpty(o.getSummary()))
                        .collect(Collectors.toList());
            }
            apis.addAll(controllerApis);
        }
        return StepResult.ok(apis);
    }

    /**
     * ????????????
     */
    private StepResult<YapixConfig> resolveConfig(EventData data) {
        // ??????????????????
        VirtualFile file = YapixConfigUtils.findConfigFile(data.project, data.module);
        if (requiredConfigFile && (file == null || !file.exists())) {
            NotificationUtils.notify(NotificationType.WARNING, "",
                    "Not found config file .yapix",
                    new CreateConfigFileAction(data.project, data.module, "Create Config File"));
            return StepResult.stop();
        }
        YapixConfig config = null;
        if (file != null && file.exists()) {
            try {
                config = YapixConfigUtils.readYapixConfig(file);
            } catch (Exception e) {
                notifyError(String.format("Config file error: %s", e.getMessage()));
                return StepResult.stop();
            }
        }
        if (config == null) {
            config = new YapixConfig();
        }
        config = YapixConfig.getMergedInternalConfig(config);
        return StepResult.ok(config);
    }


    /**
     * ????????????????????????
     *
     * @param project     ??????
     * @param apis        ?????????????????????
     * @param apiConsumer ???????????????????????????
     * @param afterAction ?????????????????????????????????????????????????????????????????????
     */
    protected void handleUploadAsync(Project project, List<Api> apis, Function<Api, ApiUploadResult> apiConsumer,
            Supplier<?> afterAction) {
        // ????????????
        ProgressManager.getInstance().run(new Task.Backgroundable(project, DefaultConstants.NAME) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(false);
                int poolSize = apis.size() == 1 ? 1 : 4;
                // ???????????????
                Semaphore semaphore = new Semaphore(poolSize);
                ExecutorService threadPool = Executors.newFixedThreadPool(poolSize);
                double step = 1.0 / apis.size();
                AtomicInteger count = new AtomicInteger();
                AtomicDouble fraction = new AtomicDouble();

                List<ApiUploadResult> urls = null;
                try {
                    List<Future<ApiUploadResult>> futures = Lists.newArrayListWithExpectedSize(apis.size());
                    for (int i = 0; i < apis.size() && !indicator.isCanceled(); i++) {
                        Api api = apis.get(i);
                        semaphore.acquire();
                        Future<ApiUploadResult> future = threadPool.submit(() -> {
                            try {
                                // ??????
                                String text = format("[%d/%d] %s %s", count.incrementAndGet(), apis.size(),
                                        api.getMethod(), api.getPath());
                                indicator.setText(text);
                                return apiConsumer.apply(api);
                            } catch (Exception e) {
                                notifyError(
                                        String.format("Upload failed: [%s %s]", api.getMethod(), api.getPath()),
                                        ExceptionUtils.getStackTrace(e));
                            } finally {
                                indicator.setFraction(fraction.addAndGet(step));
                                semaphore.release();
                            }
                            return null;
                        });
                        futures.add(future);
                    }
                    urls = ConcurrentUtils.waitFuturesSilence(futures).stream()
                            .filter(Objects::nonNull).collect(Collectors.toList());
                } catch (InterruptedException e) {
                    // ignore
                } finally {
                    if (urls != null && !urls.isEmpty()) {
                        ApiUploadResult uploadResult = urls.get(0);
                        String url = urls.size() == 1 ? uploadResult.getApiUrl() : uploadResult.getCategoryUrl();
                        notifyInfo("Upload successful", format("<a href=\"%s\">%s</a>", url, url));
                    }
                    threadPool.shutdown();
                    afterAction.get();
                }
            }
        });
    }


    public static class ApiUploadResult {

        private String categoryUrl;
        private String apiUrl;

        //------------------ generated ------------------------//

        public String getCategoryUrl() {
            return categoryUrl;
        }

        public void setCategoryUrl(String categoryUrl) {
            this.categoryUrl = categoryUrl;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }
    }

    static class EventData {

        /**
         * ?????????
         */
        AnActionEvent event;
        /**
         * ??????
         */
        Project project;

        /**
         * ??????
         */
        Module module;

        /**
         * ???????????????
         */
        VirtualFile[] selectedFiles;

        /**
         * ?????????Java??????
         */
        List<PsiJavaFile> selectedJavaFiles;

        /**
         * ?????????
         */
        PsiClass selectedClass;

        /**
         * ????????????
         */
        PsiMethod selectedMethod;

        /**
         * ??????????????????????????????
         */
        public boolean shouldHandle() {
            return project != null && module != null && (selectedJavaFiles != null || selectedClass != null);
        }

        /**
         * ???????????????????????????????????????
         */
        public static EventData of(AnActionEvent event) {
            EventData data = new EventData();
            data.event = event;
            data.project = event.getData(CommonDataKeys.PROJECT);
            data.module = event.getData(LangDataKeys.MODULE);
            data.selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (data.project != null && data.selectedFiles != null) {
                data.selectedJavaFiles = PsiFileUtils.getPsiJavaFiles(data.project, data.selectedFiles);
            }
            Editor editor = event.getDataContext().getData(CommonDataKeys.EDITOR);
            PsiFile editorFile = event.getDataContext().getData(CommonDataKeys.PSI_FILE);
            if (editor != null && editorFile != null) {
                PsiElement referenceAt = editorFile.findElementAt(editor.getCaretModel().getOffset());
                data.selectedClass = PsiTreeUtil.getContextOfType(referenceAt, PsiClass.class);
                data.selectedMethod = PsiTreeUtil.getContextOfType(referenceAt, PsiMethod.class);
            }
            return data;
        }
    }
}
