package com.focusit.jsflight.player;

import java.awt.EventQueue;
import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;

import javax.swing.UIManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.focusit.jsflight.player.ui.ExceptionDialog;
import com.focusit.jsflight.player.ui.MainFrame;

public class Player
{
    private static final Logger log = LoggerFactory.getLogger(Player.class);

    public static String firefoxPath = null;

    public static void main(String[] args) throws IOException
    {

        firefoxPath = args[0];

        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler()
        {

            @Override
            public void uncaughtException(Thread t, Throwable e)
            {
                log.error(e.toString(), e);
            	ExceptionDialog ld = new ExceptionDialog("Error", e.toString(), e);
            	ld.setVisible(true);
            }
        });
        EventQueue.invokeLater(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                	UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    MainFrame window = new MainFrame();
                    window.getFrame().setVisible(true);                    
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        });
    }
}
