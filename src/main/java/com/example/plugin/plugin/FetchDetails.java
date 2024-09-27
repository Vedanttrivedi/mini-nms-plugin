package com.example.plugin.plugin;

import com.example.plugin.models.Cpu_Metrics;
import com.example.plugin.models.Memory_Metrics;
import com.jcraft.jsch.*;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.io.*;
import java.util.*;

public class FetchDetails extends AbstractVerticle
{

  // CPU metrics command
  String[] cpuMetricsCommand = {
    "top -bn1 | grep '%Cpu' | awk '{print $2}'",   // CPU spent in system processes
    "uptime | awk -F'load average:' '{print $2}' | awk '{print $1}'", // 1-minute load average
    "ps aux | wc -l",                             // Count total no of running processes
    "ps -eLF | wc -l",                            // Count total threads
    "iostat | awk 'NR==4 {print $4}'"             // I/O wait
  };

  String memoryCommand = "free"; // Single free command to get all memory-related metrics

  String diskSpaceCommand = "df | awk 'NR==4 {print $2}'";

  @Override
  public void start(Promise<Void> startPromise) throws Exception
  {

    vertx.eventBus().<JsonObject>localConsumer("fetch-send", device->{

      System.out.println("I received information to collect");

      var jsonDevice = device.body();

      var deviceMetricData=   connectAndExecuteCommands(jsonDevice.getString("username"),
        jsonDevice.getString("password"),jsonDevice.getString("ip"),jsonDevice.getString("metric") );

      deviceMetricData.put("metric",jsonDevice.getString("metric"));

      vertx.eventBus().send("send",deviceMetricData);

    });

  }

  public JsonObject connectAndExecuteCommands(String username, String password, String ip, String metric) {
    try
    {
      var jsch = new JSch();

      var session = jsch.getSession(username, ip, 22);

      session.setTimeout(2000);

      session.setPassword(password);

      session.setConfig("StrictHostKeyChecking", "no");

      session.connect();

      var commands = new ArrayList<String>();

      // Add commands based on the metric type
      if (metric.equals("memory"))
      {
        commands.add(memoryCommand);

        commands.add(diskSpaceCommand); // Execute disk space command separately
      }
      else if (metric.equals("cpu"))
      {
        commands.addAll(Arrays.asList(cpuMetricsCommand)); // Add CPU commands
      }

      List<String> output = executeCommands(session, commands);

      System.out.println("Output Commands "+output);

      session.disconnect();

      if (metric.equals("memory"))
      {
        var memoryJsonDevice =  parseMemoryMetrics(output, ip);

        return memoryJsonDevice;

      }
      else if (metric.equals("cpu"))
      {
        var cpuJsonDevice =  parseCpuMetrics(output, ip);

        return cpuJsonDevice;
      }

    }
    catch (Exception exception)
    {
      System.out.println("Exception: " + exception.getMessage());
      return new JsonObject(); // Return an empty JSON object on error
    }

    return new JsonObject();
  }

  // Method to execute a list of commands more efficiently
  private static List<String> executeCommands(Session session, List<String> commands)
  {
    var results = new ArrayList<String>();

    Channel channel = null;

    BufferedReader reader = null;

    try
    {
      for (String command : commands)
      {
        channel = session.openChannel("exec");

        ((ChannelExec) channel).setCommand(command);

        InputStream input = channel.getInputStream();

        channel.connect();

        reader = new BufferedReader(new InputStreamReader(input));

        StringBuilder output = new StringBuilder();

        String line;

        while ((line = reader.readLine()) != null) {
          if(line.contains(","))
          {
            System.out.println("Comma Found! "+line);

            line = line.substring(0,line.length()-1);

          }
          output.append(line).append("\n");
        }

        results.add(output.toString().trim());

        channel.disconnect();
      }
    }
    catch (Exception e)
    {
      e.printStackTrace();
    }
    finally
    {
      try
      {
        if (reader != null) reader.close();
        if (channel != null) channel.disconnect();
      }
      catch (Exception e)
      {
        e.printStackTrace();
      }
    }
    return results;
  }

  private JsonObject parseMemoryMetrics(List<String> output, String ip)
  {
    var memoryOutput = output.get(0); // Memory command output

    var diskSpaceOutput = output.size() > 1 ? output.get(1) : "0"; // Disk space output (if available)

    var lines = memoryOutput.split("\n");

    // The second line (Mem:) contains free, used, and cache memory
    var memoryData = lines[1].split("\\s+");

    // The third line (Swap:) contains swap memory info
    var swapData = lines[2].split("\\s+");

    return new Memory_Metrics(
      ip,
      Integer.parseInt(memoryData[3]),  // Free memory
      Integer.parseInt(memoryData[2]),  // Used memory
      Integer.parseInt(swapData[1]),    // Swap memory
      Integer.parseInt(memoryData[5]),  // Cache memory
      Integer.parseInt(diskSpaceOutput), // Disk space
      true).toJson();
  }

  // Parsing the output of CPU metrics
  private JsonObject parseCpuMetrics(List<String> output, String ip)
  {

    return new Cpu_Metrics(
      ip,
      Float.parseFloat(output.get(0)),  // CPU system processes
      Float.parseFloat(output.get(1)),  // 1-minute load average
      Integer.parseInt(output.get(2)),  // Total processes
      Integer.parseInt(output.get(3)),  // Total threads
      Float.parseFloat(output.get(4)),  // I/O wait
      true).toJson();

  }

}





















