package com.focusit.jsflight.player.scenario;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Paths;

import org.json.JSONObject;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.focusit.jsflight.player.constants.EventType;
import com.focusit.jsflight.player.script.PlayerScriptProcessor;
import com.focusit.jsflight.player.webdriver.SeleniumDriver;

/**
 * Class that really replays an event in given scenario and given selenium driver
 * Created by doki on 05.05.16.
 */
public class ScenarioProcessor
{
    private static final Logger LOG = LoggerFactory.getLogger(ScenarioProcessor.class);

    private static WebDriver getWebDriver(UserScenario scenario, SeleniumDriver seleniumDriver, JSONObject event)
    {
        boolean firefox = scenario.getConfiguration().getCommonConfiguration().isUseFirefox();
        String path = firefox ? scenario.getConfiguration().getCommonConfiguration().getFfPath()
                : scenario.getConfiguration().getCommonConfiguration().getPjsPath();
        String proxyHost = scenario.getConfiguration().getCommonConfiguration().getProxyHost();
        String proxyPort = scenario.getConfiguration().getCommonConfiguration().getProxyPort();
        String display = scenario.getConfiguration().getCommonConfiguration().getFirefoxDisplay();
        WebDriver theWebDriver = seleniumDriver.getDriverForEvent(event, firefox, path, display, proxyHost, proxyPort);

        return theWebDriver;
    }

    /**
     * Method that check if a browser has error dialog visible.
     * aand if it has then throws an exception.
     * A browser after any step should not contain any error
     * @param scenario
     * @param wd
     * @throws Exception
     */
    protected void hasBrowserAnError(UserScenario scenario, WebDriver wd) throws Exception
    {
        try
        {
            Object result = new PlayerScriptProcessor(scenario).executeWebLookupScript(
                    scenario.getConfiguration().getWebConfiguration().getFindBrowserErrorScript(), wd, null, null);
            if (Boolean.parseBoolean(result.toString()))
            {
                throw new IllegalStateException("Browser contains some error after step processing");
            }
        }
        catch (Exception e)
        {
            LOG.debug("Tried to find an error dialog " + e.toString(), e);
        }
    }

    /**
     * This method will decide whether step processing should be terminatedd at current step or not.
     * Or, in other words, should an exception be there or not.
     * Default implementation just logs
     * @param position
     * @param ex
     * @throws Exception
     */
    protected void processClickException(int position, Exception ex) throws Exception
    {
        LOG.error("Failed to process step: " + position, ex);
    }

    /**
     * Make a screenshot and save to a file
     * @param scenario
     * @param seleniumDriver
     * @param theWebDriver
     * @param position
     */
    protected void makeAShot(UserScenario scenario, SeleniumDriver seleniumDriver, WebDriver theWebDriver, int position)
    {
        if (scenario.getConfiguration().getCommonConfiguration().getMakeShots())
        {
            String screenDir = scenario.getConfiguration().getCommonConfiguration().getScreenDir();
            File dir = new File(
                    screenDir + File.separator + Paths.get(scenario.getScenarioFilename()).getFileName().toString());

            if (!dir.exists() && !dir.mkdirs())
            {
                return;
            }

            try (FileOutputStream fos = new FileOutputStream(
                    new String(dir.getAbsolutePath() + File.separator + String.format("%05d", position) + ".png")))
            {
                seleniumDriver.makeAShot(theWebDriver, fos);
            }
            catch (IOException e)
            {
                LOG.error(e.toString(), e);
            }
        }
    }

    public void applyStep(UserScenario scenario, SeleniumDriver seleniumDriver, int position)
    {
        JSONObject event = scenario.getStepAt(position);
        scenario.getContext().setCurrentScenarioStep(event);

        new PlayerScriptProcessor(scenario).runStepPrePostScript(event, position, true);
        event = new PlayerScriptProcessor(scenario).runStepTemplating(scenario, event);
        boolean error = false;
        try
        {
            if (scenario.isStepDuplicates(scenario.getConfiguration().getWebConfiguration().getDuplicationScript(),
                    event))
            {
                LOG.warn("Event duplicates prev");
                return;
            }
            String eventType = event.getString("type");

            if (scenario.isEventIgnored(eventType) || scenario.isEventBad(event))
            {
                LOG.warn("Event is ignored or bad");
                return;
            }

            String type = event.getString("type");

            if (type.equalsIgnoreCase(EventType.SCRIPT))
            {
                new PlayerScriptProcessor(scenario).executeScriptEvent(
                        scenario.getConfiguration().getScriptEventConfiguration().getScript(), event);
                return;
            }

            WebDriver theWebDriver = getWebDriver(scenario, seleniumDriver, event);

            //Configure webdriver for this event, setting params here so we can change parameters while playback is
            //paused
            seleniumDriver.setPageTimeoutMs(
                    Integer.parseInt(scenario.getConfiguration().getCommonConfiguration().getPageReadyTimeout()));
            seleniumDriver.setCheckPageJs(scenario.getConfiguration().getCommonConfiguration().getCheckPageJs());
            seleniumDriver
                    .setMaxElementGroovy(scenario.getConfiguration().getCommonConfiguration().getMaxElementGroovy());
            seleniumDriver.setLookupScript(scenario.getConfiguration().getWebConfiguration().getLookupScript());
            seleniumDriver.setUiShownScript(scenario.getConfiguration().getCommonConfiguration().getUiShownScript());

            seleniumDriver.openEventUrl(theWebDriver, event);

            seleniumDriver.setUseRandomChars(scenario.getConfiguration().getCommonConfiguration().isUseRandomChars());

            String target = scenario.getTargetForEvent(event);

            LOG.info("Event type: {}", type);
            seleniumDriver.waitPageReady(theWebDriver, event);

            try
            {
                switch (type)
                {
                case EventType.MOUSEWHEEL:
                    seleniumDriver.processMouseWheel(theWebDriver, event, target);
                    break;
                case EventType.SCROLL_EMULATION:
                    seleniumDriver.processScroll(theWebDriver, event, target);
                    break;

                case EventType.MOUSEDOWN:
                case EventType.CLICK:
                    seleniumDriver.processMouseEvent(theWebDriver, event);
                    seleniumDriver.waitPageReady(theWebDriver, event);
                    break;
                case EventType.KEY_UP:
                case EventType.KEY_DOWN:
                case EventType.KEY_PRESS:
                    seleniumDriver.processKeyboardEvent(theWebDriver, event);
                    seleniumDriver.waitPageReady(theWebDriver, event);
                    break;
                default:
                    break;
                }

                makeAShot(scenario, seleniumDriver, theWebDriver, position);

                hasBrowserAnError(scenario, theWebDriver);
            }
            catch (Exception e)
            {
                processClickException(position, e);
            }

            seleniumDriver.releaseBrowser(theWebDriver,
                    scenario.getConfiguration().getCommonConfiguration().getFormOrDialogXpath());
        }
        catch (Exception e)
        {
            error = true;
            throw new RuntimeException(e);
        }
        finally
        {
            if (!error)
            {
                scenario.updatePrevEvent(event);
                new PlayerScriptProcessor(scenario).runStepPrePostScript(event, position, false);
            }
        }
    }

    public void play(UserScenario scenario, SeleniumDriver seleniumDriver)
    {
        long begin = System.currentTimeMillis();

        LOG.info("playing the scenario");

        while (scenario.getPosition() < scenario.getStepsCount())
        {
            if (scenario.getPosition() > 0)
            {
                LOG.info("Step " + scenario.getPosition());
            }
            else
            {
                LOG.info("Step 0");
            }
            applyStep(scenario, seleniumDriver, scenario.getPosition());
            if (scenario.getPosition() + 1 < scenario.getStepsCount())
            {
                scenario.next();
            }
            else
            {
                break;
            }
        }
        scenario.next();
        LOG.info(String.format("Done(%d):playing", System.currentTimeMillis() - begin));
        seleniumDriver.closeWebDrivers();
    }

    public void play(UserScenario scenario, SeleniumDriver seleniumDriver, int start, int finish)
    {
        long begin = System.currentTimeMillis();

        LOG.info("playing the scenario");
        if (start > 0)
        {
            LOG.info("skiping " + start + " events.");
            scenario.setPosition(start);
        }

        int maxPosition = scenario.getStepsCount();

        if (finish > 0)
        {
            maxPosition = finish;
        }

        while (scenario.getPosition() < maxPosition)
        {
            if (scenario.getPosition() > 0)
            {
                LOG.info("Step " + scenario.getPosition());
            }
            else
            {
                LOG.info("Step 0");
            }
            applyStep(scenario, seleniumDriver, scenario.getPosition());
            if (scenario.getPosition() + 1 < maxPosition)
            {
                scenario.next();
            }
            else
            {
                break;
            }
        }
        scenario.next();
        LOG.info(String.format("Done(%d):playing", System.currentTimeMillis() - begin));
        seleniumDriver.closeWebDrivers();
    }

}
