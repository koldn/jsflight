package com.focusit.jsflight.player.webdriver;

import com.focusit.jsflight.player.constants.BrowserType;
import com.focusit.jsflight.player.constants.EventConstants;
import com.focusit.jsflight.player.constants.EventType;
import com.focusit.jsflight.player.scenario.UserScenario;
import com.focusit.jsflight.player.script.PlayerScriptProcessor;
import com.focusit.jsflight.script.constants.ScriptBindingConstants;
import com.google.common.base.Predicate;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Selenium webdriver proxy: runs a browser, sends events, make screenshots
 *
 * @author Denis V. Kirpichenkov
 */
public class SeleniumDriver
{

    /**
     * Non operational element indicating step processing must be aborted
     * returned from element lookup script
     */
    public static final RemoteWebElement NO_OP_ELEMENT = new RemoteWebElement();
    private static final Logger LOG = LoggerFactory.getLogger(SeleniumDriver.class);

    /**
     * You must start Xvfb displays from 1 up to 200, i.e [1;200)
     */
    private static final int DISPLAY_CAPACITY = 200;

    private static final String UI_NOT_SHOWED_UP_MSG = "UI didn't show up";

    private static final int PROCESS_SIGNAL_STOP = -19;
    private static final int PROCESS_SIGNAL_CONT = -18;
    private static final int PROCESS_SIGNAL_FORCE_KILL = -9;

    static
    {
        //This must be set due to equals of WebElement
        NO_OP_ELEMENT.setId("NO_OP");
    }

    /**
     * As we close unsued browsers, 200 number of displays is more than enough
     */
    private Map<String, Integer> availableDisplays = new HashMap<>(DISPLAY_CAPACITY);
    private HashMap<String, String> driverDisplay = new HashMap<>();
    private HashMap<String, WebDriver> tabUuidDrivers = new HashMap<>();
    private Map<String, String> lastUrls = new HashMap<>();

    private boolean useRandomStringGenerator;
    private StringGenerator stringGenerator;

    private UserScenario scenario;

    private int asyncRequestsCompletedTimeoutInMs;
    private String isAsyncRequestsCompletedScript;

    private long intervalBetweenUiChecksInMs;

    private int uiShownTimeoutInSeconds;
    private String isUiShownScript;

    private List<String> placeholders;

    private String isSelectElementScript;
    private String sendSignalToProcessScript;
    private String getWebDriverPidScript;
    private String elementLookupScript;
    private String skipKeyboardScript;
    private String maxElementGroovy;

    private String selectXpath;
    private String keepBrowserXpath;

    public SeleniumDriver(UserScenario scenario)
    {
        this(scenario, 0, 0);
    }

    public SeleniumDriver(UserScenario scenario, Integer xvfbDisplayLowerBound, Integer xvfbDisplayUpperBound)
    {
        this.scenario = scenario;

        for (int i = xvfbDisplayLowerBound; i <= xvfbDisplayUpperBound; i++)
        {
            availableDisplays.put(":" + i, 0);
        }
    }

    public SeleniumDriver setKeepBrowserXpath(String keepBrowserXpath)
    {
        this.keepBrowserXpath = keepBrowserXpath;
        return this;
    }

    public SeleniumDriver setSendSignalToProcessScript(String sendSignalToProcessScript)
    {
        this.sendSignalToProcessScript = sendSignalToProcessScript;
        return this;
    }

    public SeleniumDriver setIsSelectElementScript(String isSelectElementScript)
    {
        this.isSelectElementScript = isSelectElementScript;
        return this;
    }

    public SeleniumDriver setSelectXpath(String selectXpath)
    {
        this.selectXpath = selectXpath;
        return this;
    }

    public SeleniumDriver setPlaceholders(String placeholders)
    {
        this.placeholders = Arrays.asList(placeholders.split(","));
        return this;
    }

    public int getUiShownTimeoutInSeconds()
    {
        return uiShownTimeoutInSeconds;
    }

    public SeleniumDriver setUiShownTimeoutInSeconds(int uiShownTimeoutInSeconds)
    {
        this.uiShownTimeoutInSeconds = uiShownTimeoutInSeconds;
        return this;
    }

    public SeleniumDriver setLastUrls(Map<String, String> externalUrls)
    {
        this.lastUrls = externalUrls;
        return this;
    }

    public SeleniumDriver setUseRandomStringGenerator(boolean useRandomStringGenerator)
    {
        this.useRandomStringGenerator = useRandomStringGenerator;
        return this;
    }

    public SeleniumDriver setAsyncRequestsCompletedTimeoutInMs(int asyncRequestsCompletedTimeoutInMs)
    {
        this.asyncRequestsCompletedTimeoutInMs = asyncRequestsCompletedTimeoutInMs;
        return this;
    }

    public SeleniumDriver setGetWebDriverPidScript(String getWebDriverPidScript)
    {
        this.getWebDriverPidScript = getWebDriverPidScript;
        return this;
    }

    public SeleniumDriver setIsAsyncRequestsCompletedScript(String isAsyncRequestsCompletedScript)
    {
        this.isAsyncRequestsCompletedScript = isAsyncRequestsCompletedScript;
        return this;
    }

    public SeleniumDriver setElementLookupScript(String elementLookupScript)
    {
        this.elementLookupScript = elementLookupScript;
        return this;
    }

    public SeleniumDriver setIsUiShownScript(String isUiShownScript)
    {
        this.isUiShownScript = isUiShownScript;
        return this;
    }

    public SeleniumDriver setMaxElementGroovy(String maxElementGroovy)
    {
        this.maxElementGroovy = maxElementGroovy;
        return this;
    }

    public void closeWebDrivers()
    {
        PlayerScriptProcessor processor = new PlayerScriptProcessor(scenario);
        tabUuidDrivers.values().forEach(
                driver -> {
                    String pid = getFirefoxPid(driver);
                    processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_CONT, pid);
                    processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_FORCE_KILL, pid);
                });
    }

    public WebElement findTargetWebElement(WebDriver wd, JSONObject event, String target)
    {
        waitWhileAsyncRequestsWillCompletedWithRefresh(wd, event);

        Map<String, Object> binding = PlayerScriptProcessor.getEmptyBindingsMap();
        binding.put(ScriptBindingConstants.WEB_DRIVER, wd);
        binding.put(ScriptBindingConstants.TARGET, target);
        binding.put(ScriptBindingConstants.EVENT, event);
        WebElement webElement = new PlayerScriptProcessor(scenario).executeGroovyScript(elementLookupScript, binding,
                WebElement.class);
        if (webElement == null)
        {
            throw new IllegalStateException("Element lookup script returned null");
        }
        return webElement;
    }

    public WebDriver getDriverForEvent(JSONObject event, BrowserType browserType, String path,
            String proxyHost, Integer proxyPort)
    {
        String tabUuid = event.getString(EventConstants.TAB_UUID);
        WebDriver driver = tabUuidDrivers.get(tabUuid);

        try
        {
            if (driver != null)
            {
                return driver;
            }

            DesiredCapabilities cap = new DesiredCapabilities();
            if (!StringUtils.isBlank(proxyHost))
            {
                String host = proxyHost;
                if (proxyPort != 0)
                {
                    host += ":" + proxyPort;
                }
                Proxy proxy = new Proxy();
                proxy.setHttpProxy(host).setFtpProxy(host).setSslProxy(host);
                cap.setCapability(CapabilityType.PROXY, proxy);
            }

            String display = availableDisplays.keySet().stream()
                    .min((one, other) -> availableDisplays.get(one) - availableDisplays.get(other)).get();

            switch (browserType)
            {
            case FIREFOX:
                FirefoxProfile profile = createDefaultFirefoxProfile();
                FirefoxBinary binary = !StringUtils.isBlank(path) ? new FirefoxBinary(new File(path))
                        : new FirefoxBinary();
                LOG.info("Binding to {} display", display);
                availableDisplays.computeIfPresent(display, (d, value) -> value + 1);
                binary.setEnvironmentProperty("DISPLAY", display);
                LOG.info("Firefox path is: {}", path);

                driver = createFirefoxDriver(cap, profile, binary);
                break;
            case CHROME:
                throw new RuntimeException("Chrome web driver can't be used now");
            }
            driver = WebDriverWrapper.wrap(driver);

            tabUuidDrivers.put(tabUuid, driver);

            //as actual webdriver is RemoteWebdriver, calling to string return browser name, platform and sessionid
            //which are not subject to change, so we can use it as key;
            driverDisplay.put(driver.toString(), display);
            return driver;
        }
        catch (Throwable ex)
        {
            LOG.error(ex.toString(), ex);
            throw ex;
        }
        finally
        {
            prioritize(driver);
        }
    }

    private FirefoxDriver createFirefoxDriver(DesiredCapabilities cap, FirefoxProfile profile, FirefoxBinary binary)
    {
        try
        {
            return new FirefoxDriver(binary, profile, cap);
        }
        catch (WebDriverException ex)
        {
            LOG.warn(ex.getMessage());
            awakenAllDrivers();
            return createFirefoxDriver(cap, profile, binary);
        }
    }

    public String getDriverDisplay(WebDriver webdriver)
    {
        return driverDisplay.getOrDefault(webdriver.toString(), ":0");
    }

    public String getLastUrl(JSONObject event)
    {
        String tag = UserScenario.getTagForEvent(event);
        String result = lastUrls.get(tag);
        if (result == null)
        {
            String empty = "";
            lastUrls.put(tag, empty);
            result = empty;
        }

        return result;
    }

    public void makeAShot(WebDriver wd, OutputStream outputStream) throws IOException
    {
        TakesScreenshot shooter = (TakesScreenshot)wd;
        byte[] shot = shooter.getScreenshotAs(OutputType.BYTES);
        outputStream.write(shot);
    }

    public void openEventUrl(WebDriver wd, JSONObject event)
    {
        String eventUrl = event.getString(EventConstants.URL);

        resizeForEvent(wd, event);
        if (wd.getCurrentUrl().equals("about:blank") || !getLastUrl(event).equals(eventUrl))
        {
            wd.get(eventUrl);

            waitWhileUiWillShown(wd);
            updateLastUrl(event, eventUrl);
        }
    }

    public WebElement waitElement(WebDriver wd, String xpath)
    {
        try
        {
            return new WebDriverWait(wd, 20L, 500).until(new ExpectedCondition<WebElement>()
            {
                @Override
                public WebElement apply(WebDriver input)
                {
                    try
                    {
                        return wd.findElement(By.xpath(xpath));
                    }
                    catch (NoSuchElementException e)
                    {
                        return null;
                    }
                }
            });
        }
        catch (TimeoutException e)
        {
            throw new NoSuchElementException("Element was not found within timeout. Xpath " + xpath);
        }
    }

    public void processKeyboardEvent(WebDriver wd, JSONObject event) throws UnsupportedEncodingException
    {
        WebElement element = findTargetWebElement(wd, event, UserScenario.getTargetForEvent(event));
        if (isNoOp(event, element))
        {
            return;
        }
        initializeStringGenerator(useRandomStringGenerator);

        //TODO remove this when recording of cursor in text box is implemented
        if (skipKeyboardForElement(element))
        {
            LOG.warn("Keyboard processing for non empty Date is disabled");
            return;
        }

        //Before processing keyboard events focus MUST be on the browser window, otherwise there is no
        //guaranties for correct processing.
        //Firefox ignores all fired events when window is not in focus
        //Selenium uses dark magic to deal with it
        wd.switchTo().window(wd.getWindowHandle());

        if (event.getString(EventConstants.TYPE).equalsIgnoreCase(EventType.KEY_PRESS))
        {
            if (event.has(EventConstants.CHAR_CODE))
            {
                char ch = (char)event.getBigInteger(EventConstants.CHAR_CODE).intValue();
                String keys = stringGenerator.getAsString(ch);
                LOG.info("Trying to fill input with: {}", keys);
                if (element.getTagName().contains("iframe"))
                {
                    LOG.info("Input is iframe");
                    WebDriver frame = wd.switchTo().frame(element);
                    WebElement editor = frame.findElement(By.tagName("body"));
                    editor.sendKeys(keys);
                    wd.switchTo().defaultContent();
                }
                else
                {
                    LOG.info("Input is ordinary input");
                    String prevText = element.getAttribute("value");
                    //If current value indicates a placeholder it must be discarded
                    if (placeholders.contains(prevText))
                    {
                        prevText = "";
                    }
                    element.clear();
                    element.sendKeys(prevText + keys);
                }
            }
        }

        if (event.getString(EventConstants.TYPE).equalsIgnoreCase(EventType.KEY_UP)
                || event.getString(EventConstants.TYPE).equalsIgnoreCase(EventType.KEY_DOWN))
        {
            if (event.has(EventConstants.CHAR_CODE))
            {
                int code = event.getBigInteger(EventConstants.CHAR_CODE).intValue();
                if (code == 0)
                {
                    code = event.getInt(EventConstants.KEY_CODE);
                }
                if (event.getBoolean(EventConstants.CTRL_KEY))
                {
                    element.sendKeys(Keys.chord(Keys.CONTROL, new String(new byte[] { (byte)code },
                            StandardCharsets.UTF_8)));
                }
                else
                {
                    switch (code)
                    {
                    case 8:
                        element.sendKeys(Keys.BACK_SPACE);
                        break;
                    case 27:
                        element.sendKeys(Keys.ESCAPE);
                        break;
                    case 46:
                        element.sendKeys(Keys.DELETE);
                        break;
                    case 13:
                        element.sendKeys(Keys.ENTER);
                        break;
                    case 37:
                        element.sendKeys(Keys.ARROW_LEFT);
                        break;
                    case 38:
                        element.sendKeys(Keys.ARROW_UP);
                        break;
                    case 39:
                        element.sendKeys(Keys.ARROW_RIGHT);
                        break;
                    case 40:
                        element.sendKeys(Keys.ARROW_DOWN);
                        break;
                    default:
                        break;
                    }
                }
            }
        }
    }

    private boolean isNoOp(JSONObject event, WebElement element)
    {
        if (element.equals(NO_OP_ELEMENT))
        {
            LOG.warn("Non operational element returned. Aborting event {} processing. Target xpath {}",
                    event.get(EventConstants.EVENT_ID), event.getString(EventConstants.SECOND_TARGET));
            return true;
        }
        return false;
    }

    public void processMouseEvent(WebDriver wd, JSONObject event)
    {
        WebElement element = findTargetWebElement(wd, event, UserScenario.getTargetForEvent(event));
        if (isNoOp(event, element))
        {
            return;
        }
        ensureElementInWindow(wd, element);
        boolean isSelect = new PlayerScriptProcessor(scenario).executeSelectDeterminerScript(isSelectElementScript, wd,
                element);
        click(wd, event, element);
        if (isSelect)
        {
            //Wait for select to popup
            LOG.debug("Mouse event is kind of select");
            waitElement(wd, selectXpath);
        }

    }

    private void click(WebDriver wd, JSONObject event, WebElement element)
    {
        if (element.isDisplayed())
        {

            if (event.getInt(EventConstants.BUTTON) == 2)
            {
                try
                {
                    new Actions(wd).contextClick(element).perform();
                }
                catch (WebDriverException ex)
                {
                    try
                    {
                        LOG.warn("Error simulation right click. Retrying after 2 sec.");
                        Thread.sleep(2000);

                        new Actions(wd).contextClick(element).perform();
                    }
                    catch (Exception e)
                    {
                        LOG.error("Error while simulating right click.", e);
                    }
                }
            }
            else
            {
                element.click();
            }
        }
        else
        {
            JavascriptExecutor executor = (JavascriptExecutor)wd;
            executor.executeScript("arguments[0].click();", element);
        }
    }

    public void processMouseWheel(WebDriver wd, JSONObject event, String target)
    {
        if (!event.has(EventConstants.DELTA_Y))
        {
            LOG.error("event has no deltaY - cant process scroll", new Exception());
            return;
        }
        WebElement el = findTargetWebElement(wd, event, target);
        if (isNoOp(event, el))
        {
            return;
        }
        //Web lookup script MUST return //html element if scroll occurs not in a popup
        if (!el.getTagName().equalsIgnoreCase("html"))
        {
            ((JavascriptExecutor)wd).executeScript("arguments[0].scrollTop = arguments[0].scrollTop + arguments[1]",
                    el, event.getInt(EventConstants.DELTA_Y));
        }
        else
        {
            ((JavascriptExecutor)wd).executeScript("window.scrollBy(0, arguments[0])",
                    event.getInt(EventConstants.DELTA_Y));
        }
    }

    public void processScroll(WebDriver wd, JSONObject event, String target)
    {
        long timeout = System.currentTimeMillis() + 20000L;
        if (checkElementPresent(wd, target))
        {
            return;
        }
        do
        {
            waitWhileAsyncRequestsWillCompletedWithRefresh(wd, event);
            try
            {
                WebElement el = getMax(wd, maxElementGroovy);
                scroll((JavascriptExecutor)wd, el);
                if (checkElementPresent(wd, target))
                {
                    return;
                }
            }
            catch (Exception ex)
            {
                LOG.error(ex.toString(), ex);
            }
        }
        while (System.currentTimeMillis() < timeout);
        throw new NoSuchElementException("Element was not found during scroll");
    }

    public void releaseBrowser(WebDriver driver, JSONObject event)
    {
        if (shouldKeepBrowser(driver))
        {
            LOG.debug("Keep browser xpath matches some element, or it wasn't specified");
            return;
        }

        String display = driverDisplay.get(driver.toString());
        if (display != null)
        {
            availableDisplays.put(display, availableDisplays.get(display) - 1);
            LOG.info("Display {} is used {} times now", display, availableDisplays.get(display));
        }
        PlayerScriptProcessor processor = new PlayerScriptProcessor(scenario);
        String firefoxPid = getFirefoxPid(driver);
        processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_CONT, firefoxPid);

        String tabUuid = event.getString(EventConstants.TAB_UUID);
        tabUuidDrivers.remove(tabUuid);
        driver.quit();
        try
        {
            LOG.info("Trying to kill Firefox. PID: {}", firefoxPid);
            processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_FORCE_KILL, firefoxPid);
        }
        catch (Throwable ex)
        {
            LOG.error(ex.getMessage(), ex);
        }
    }

    private String getFirefoxPid(WebDriver driver)
    {
        PlayerScriptProcessor processor = new PlayerScriptProcessor(scenario);
        Map<String, Object> binding = PlayerScriptProcessor.getEmptyBindingsMap();
        binding.put(ScriptBindingConstants.WEB_DRIVER, getSeleniumDriver(driver));
        return processor.executeGroovyScript(getWebDriverPidScript, binding, String.class);
    }

    private WebDriver getSeleniumDriver(WebDriver driver)
    {
        return driver instanceof WebDriverWrapper ? ((WebDriverWrapper)driver).getWrappedDriver() : driver;
    }

    public void resetLastUrls()
    {
        lastUrls.clear();
    }

    public void setScenario(UserScenario scenario)
    {
        this.scenario = scenario;
    }

    public void updateLastUrl(JSONObject event, String url)
    {
        lastUrls.put(UserScenario.getTagForEvent(event), url);
    }

    public void waitWhileAsyncRequestsWillCompletedWithRefresh(WebDriver wd, JSONObject event)
    {
        try
        {
            waitWhileAsyncRequestsWillCompleted(wd, event);
        }
        catch (IllegalStateException e)
        {
            if (shouldKeepBrowser(wd))
            {
                throw e;
            }
            wd.navigate().refresh();
            waitWhileAsyncRequestsWillCompleted(wd, event);
        }
    }

    private boolean shouldKeepBrowser(WebDriver wd)
    {
        return keepBrowserXpath != null && !keepBrowserXpath.isEmpty()
                && !wd.findElements(By.xpath(keepBrowserXpath)).isEmpty();
    }

    public void waitWhileAsyncRequestsWillCompleted(WebDriver wd, JSONObject event)
    {
        String type = event.getString(EventConstants.TYPE);
        if (type.equalsIgnoreCase(EventType.XHR) || type.equalsIgnoreCase(EventType.SCRIPT))
        {
            return;
        }
        LOG.info("Waiting while async requests will completed for {} seconds", asyncRequestsCompletedTimeoutInMs);
        try
        {
            new WebDriverWait(wd, asyncRequestsCompletedTimeoutInMs, 500).until((Predicate<WebDriver>)input -> {
                try
                {
                    Object result = ((JavascriptExecutor)wd).executeScript(isAsyncRequestsCompletedScript);
                    return result != null && Boolean.parseBoolean(result.toString().toLowerCase());
                }
                catch (WebDriverException e)
                {
                    return false;
                }
            });
        }
        catch (TimeoutException e)
        {
            throw new IllegalStateException(String.format(
                    "Async requests was not completed within specified timeout (%ds): %s",
                    asyncRequestsCompletedTimeoutInMs, event.getString(EventConstants.URL)));
        }
    }

    private boolean checkElementPresent(WebDriver wd, String target)
    {
        try
        {
            wd.findElement(By.xpath(target));
            return true;
        }
        catch (Exception e)
        {
            return false;
        }
    }

    private void ensureElementInWindow(WebDriver wd, WebElement element)
    {
        int windowHeight = wd.manage().window().getSize().getHeight();
        int elementY = element.getLocation().getY();
        if (elementY > windowHeight)
        {
            //Using division of the Y coordinate by 2 ensures target element visibility in the browser view
            //anyway TODO think of not using hardcoded constants in scrolling
            String scrollScript = "window.scrollTo(0, " + elementY / 2 + ");";
            ((JavascriptExecutor)wd).executeScript(scrollScript);
        }
    }

    private void initializeStringGenerator(boolean useRandomChars)
    {
        if (stringGenerator == null)
        {
            stringGenerator = useRandomChars ? new RandomStringGenerator() : new CharStringGenerator();
        }
    }

    private boolean skipKeyboardForElement(WebElement element)
    {
        //TODO remove this when recording of cursor in text box is implemented
        Map<String, Object> bindings = PlayerScriptProcessor.getEmptyBindingsMap();
        bindings.put(ScriptBindingConstants.ELEMENT, element);
        return new PlayerScriptProcessor(scenario).executeGroovyScript(skipKeyboardScript, bindings, Boolean.class);
    }

    private WebElement getMax(WebDriver wd, String script)
    {
        Map<String, Object> binding = PlayerScriptProcessor.getEmptyBindingsMap();
        binding.put(ScriptBindingConstants.WEB_DRIVER, wd);
        return new PlayerScriptProcessor(scenario).executeGroovyScript(script, binding, WebElement.class);
    }

    private void resizeForEvent(WebDriver wd, JSONObject event)
    {
        boolean hasProperty = event.has("window.width");
        JSONObject target = hasProperty ? event : event.getJSONObject("window");
        int width = target.getInt(hasProperty ? "window.width" : "width");
        int height = target.getInt(hasProperty ? "window.height" : "height");

        width = width > 0 ? width : 1000;
        height = height > 0 ? height : 1000;

        LOG.info("Resizing to {}x{}", width, height);
        wd.manage().window().setSize(new Dimension(width, height));
    }

    private void scroll(JavascriptExecutor js, WebElement element)
    {
        js.executeScript("arguments[0].scrollIntoView(true)", element);
    }

    private void waitWhileUiWillShown(WebDriver wd)
    {
        try
        {
            LOG.info("Waiting while UI will appear for {} seconds", getUiShownTimeoutInSeconds());
            new WebDriverWait(wd, getUiShownTimeoutInSeconds(), intervalBetweenUiChecksInMs)
                    .until(new Predicate<WebDriver>()
                    {
                        @Override
                        public boolean apply(WebDriver driver)
                        {
                            try
                            {
                                Map<String, Object> binding = PlayerScriptProcessor.getEmptyBindingsMap();
                                binding.put(ScriptBindingConstants.WEB_DRIVER, driver);
                                return new PlayerScriptProcessor(scenario).executeGroovyScript(isUiShownScript,
                                        binding, Boolean.class);
                            }
                            catch (WebDriverException e)
                            {
                                return false;
                            }
                        }
                    });
        }
        catch (TimeoutException e)
        {
            LOG.error("UI not appears!");
            throw new NoSuchElementException(UI_NOT_SHOWED_UP_MSG);
        }
    }

    private void awakenAllDrivers()
    {
        PlayerScriptProcessor processor = new PlayerScriptProcessor(scenario);
        tabUuidDrivers.values().forEach(
                driver -> processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_CONT,
                        getFirefoxPid(driver)));
    }

    private void prioritize(WebDriver wd)
    {
        PlayerScriptProcessor processor = new PlayerScriptProcessor(scenario);
        String firefoxPid = getFirefoxPid(wd);
        processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_CONT, firefoxPid);
        LOG.info("Prioritizing driver with pid: {}", firefoxPid);
        tabUuidDrivers
                .values()
                .stream()
                .filter(driver -> !driver.equals(wd))
                .forEach(
                        driver -> processor.executeProcessSignalScript(sendSignalToProcessScript, PROCESS_SIGNAL_STOP,
                                getFirefoxPid(driver)));
    }

    private FirefoxProfile createDefaultFirefoxProfile()
    {
        FirefoxProfile firefoxProfile = new FirefoxProfile();
        firefoxProfile.setPreference("nglayout.initialpaint.delay", "0");
        firefoxProfile.setPreference("network.http.pipelining", true);
        firefoxProfile.setPreference("image.animation_mode", "none");
        firefoxProfile.setPreference("layers.acceleration.force-enabled", true);
        firefoxProfile.setPreference("layers.offmainthreadcomposition.enabled", true);
        firefoxProfile.setPreference("browser.sessionstore.interval", 3600000);
        firefoxProfile.setPreference("privacy.trackingprotection.enabled", true);
        firefoxProfile.setPreference("content.notify.interval", 849999);
        firefoxProfile.setPreference("content.notify.backoffcount", 5);
        firefoxProfile.setPreference("network.http.max-connections", 50);
        firefoxProfile.setPreference("network.http.max-connections-per-server", 150);
        firefoxProfile.setPreference("network.http.pipelining.aggressive", false);
        firefoxProfile.setPreference("browser.tabs.animate", false);
        firefoxProfile.setPreference("browser.display.show_image_placeholders", false);
        firefoxProfile.setPreference("browser.cache.use_new_backend", 1);
        firefoxProfile.setPreference("ui.submenuDelay", 0);
        firefoxProfile.setPreference("browser.cache.disk.enable", false);
        firefoxProfile.setPreference("browser.cache.memory.enable", true);
        firefoxProfile.setPreference("browser.cache.memory.capacity", 128000);
        return firefoxProfile;
    }

    public SeleniumDriver setIntervalBetweenUiChecksInMs(long intervalBetweenUiChecksInMs)
    {
        this.intervalBetweenUiChecksInMs = intervalBetweenUiChecksInMs;
        return this;
    }

    public SeleniumDriver setSkipKeyboardScript(String skipKeyboardScript)
    {
        this.skipKeyboardScript = skipKeyboardScript;
        return this;
    }

    private static abstract class StringGenerator
    {
        protected static final Logger LOG = LoggerFactory.getLogger(StringGenerator.class);

        public String getAsString(char ch) throws UnsupportedEncodingException
        {
            String result = generate(ch);
            LOG.info("Returning {}", result);
            return result;
        }

        protected abstract String generate(char ch) throws UnsupportedEncodingException;
    }

    private static class CharStringGenerator extends StringGenerator
    {
        @Override
        public String generate(char ch) throws UnsupportedEncodingException
        {
            return String.valueOf(ch);
        }

    }

    private static class RandomStringGenerator extends StringGenerator
    {
        @Override
        public String generate(char ch)
        {
            return RandomStringUtils.randomAlphanumeric(1);
        }

    }
}
