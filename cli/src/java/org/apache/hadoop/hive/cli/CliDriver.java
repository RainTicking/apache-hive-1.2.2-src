/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.cli;

import static org.apache.hadoop.util.StringUtils.stringifyException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Splitter;

import jline.console.ConsoleReader;
import jline.console.completer.Completer;
import jline.console.history.FileHistory;
import jline.console.history.History;
import jline.console.history.PersistentHistory;
import jline.console.completer.StringsCompleter;
import jline.console.completer.ArgumentCompleter;
import jline.console.completer.ArgumentCompleter.ArgumentDelimiter;
import jline.console.completer.ArgumentCompleter.AbstractArgumentDelimiter;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.HiveInterruptUtils;
import org.apache.hadoop.hive.common.LogUtils;
import org.apache.hadoop.hive.common.LogUtils.LogInitializationException;
import org.apache.hadoop.hive.common.cli.ShellCmdExecutor;
import org.apache.hadoop.hive.common.io.CachingPrintStream;
import org.apache.hadoop.hive.common.io.FetchConverter;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.Validator;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.hive.ql.exec.mr.HadoopJobExecHelper;
import org.apache.hadoop.hive.ql.exec.tez.TezJobExecHelper;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.VariableSubstitution;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorFactory;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.io.IOUtils;

import sun.misc.Signal;
import sun.misc.SignalHandler;


/**
 * CliDriver.
 *
 */
public class CliDriver {

  public static String prompt = null;
  public static String prompt2 = null; // when ';' is not yet seen
  public static final int LINES_TO_FETCH = 40; // number of lines to fetch in batch from remote hive server
  public static final int DELIMITED_CANDIDATE_THRESHOLD = 10;

  public static final String HIVERCFILE = ".hiverc";

  private final LogHelper console;
  protected ConsoleReader reader;
  private Configuration conf;


   // 构造函数
  public CliDriver() {
    /**
     * SessionState封装了一个会话的关联的数据，包括配置信息HiveConf，输入输出流，指令类型，用户名称、IP地址等等。
     * SessionState 是一个与线程关联的静态本地变量ThreadLocal，任何一个线程都对应一个SessionState，
     * 能够在Hive代码的任何地方获取到（大量被使用到），以返回用户相关或者配置信息等。
     */
    SessionState ss = SessionState.get();
    conf = (ss != null) ? ss.getConf() : new Configuration();
    Log LOG = LogFactory.getLog("CliDriver");
    if (LOG.isDebugEnabled()) {
      LOG.debug("CliDriver inited with classpath " + System.getProperty("java.class.path"));
    }
    console = new LogHelper(LOG);
  }

  /**
   * @param cmd
   * @return
   */
  public int processCmd(String cmd) {
    /**
     * 首先是设置当前clisession的用户上一条指令，然后使用正则表达式，
     * 将用户输入的指令从空格，制表符等出断开（tokenizeCmd函数），得到token数组。
     */
    CliSessionState ss = (CliSessionState) SessionState.get();
    ss.setLastCommand(cmd);
    // Flush the print stream, so it doesn't include output from the last command
    ss.err.flush();
    String cmd_trimmed = cmd.trim();
    String[] tokens = tokenizeCmd(cmd_trimmed);
    int ret = 0;
    /**
     * 然后根据用户的输入，进行不同的处理，这边的处理主要包括：
     * quit或exit： 关闭会话，退出hive
     * source： 文件处理？不清楚对应什么操作
     * ! 开头： 调用Linux系统的shell执行指令
     * 本地模式：创建CommandProcessor, 执行用户指令
     */
    if (cmd_trimmed.toLowerCase().equals("quit") || cmd_trimmed.toLowerCase().equals("exit")) {

      // if we have come this far - either the previous commands
      // are all successful or this is command line. in either case
      // this counts as a successful run
      ss.close();
      System.exit(0);

    } else if (tokens[0].equalsIgnoreCase("source")) {
      String cmd_1 = getFirstCmd(cmd_trimmed, tokens[0].length());
      cmd_1 = new VariableSubstitution().substitute(ss.getConf(), cmd_1);

      File sourceFile = new File(cmd_1);
      if (! sourceFile.isFile()){
        console.printError("File: "+ cmd_1 + " is not a file.");
        ret = 1;
      } else {
        try {
          ret = processFile(cmd_1);
        } catch (IOException e) {
          console.printError("Failed processing file "+ cmd_1 +" "+ e.getLocalizedMessage(),
            stringifyException(e));
          ret = 1;
        }
      }
    } else if (cmd_trimmed.startsWith("!")) {

      String shell_cmd = cmd_trimmed.substring(1);
      shell_cmd = new VariableSubstitution().substitute(ss.getConf(), shell_cmd);

      // shell_cmd = "/bin/bash -c \'" + shell_cmd + "\'";
      try {
        ShellCmdExecutor executor = new ShellCmdExecutor(shell_cmd, ss.out, ss.err);
        ret = executor.execute();
        if (ret != 0) {
          console.printError("Command failed with exit code = " + ret);
        }
      } catch (Exception e) {
        console.printError("Exception raised from Shell command " + e.getLocalizedMessage(),
            stringifyException(e));
        ret = 1;
      }
    }  else { // local mode
      try {
        /**
         * 限于篇幅原因，前面三种情况的代码不再详述，重点介绍Hive的本地模式执行，
         * 也就是我们常用的HiveQL语句，DFS命令等的处理方式：
         */
        CommandProcessor proc = CommandProcessorFactory.get(tokens, (HiveConf) conf);
        ret = processLocalCmd(cmd, proc, ss);
      } catch (SQLException e) {
        console.printError("Failed processing command " + tokens[0] + " " + e.getLocalizedMessage(),
          org.apache.hadoop.util.StringUtils.stringifyException(e));
        ret = 1;
      }
    }

    return ret;
  }

  /**
   * For testing purposes to inject Configuration dependency
   * @param conf to replace default
   */
  void setConf(Configuration conf) {
    this.conf = conf;
  }

  /**
   * Extract and clean up the first command in the input.
   */
  private String getFirstCmd(String cmd, int length) {
    return cmd.substring(length).trim();
  }

  private String[] tokenizeCmd(String cmd) {
    return cmd.split("\\s+");
  }

  /**
   * 到此Hive对用户的一个指令cmd，配置了会话状态CliSessionState，选择了一个合适的CommandProcessor，
   * CliDriver将进行他的最后一步操作，提交用户的查询到指定的CommandProcessor，并获取结果。
   * 这一切都是在processLocalCmd中执行的。
   * @param cmd
   * @param proc
   * @param ss
   * @return
   */
  int processLocalCmd(String cmd, CommandProcessor proc, CliSessionState ss) {
    int tryCount = 0;
    boolean needRetry;
    int ret = 0;

    do {
      try {
        needRetry = false;
        if (proc != null) {
          //如果CommandProcessor是Driver实例
          if (proc instanceof Driver) {
            Driver qp = (Driver) proc;
            //获取标准输出流，打印结果信息
            PrintStream out = ss.out;
            long start = System.currentTimeMillis();
            if (ss.getIsVerbose()) {
              out.println(cmd);
            }

            qp.setTryCount(tryCount);
            //driver实例运行用户指令，获取运行结果响应码
            ret = qp.run(cmd).getResponseCode();
            if (ret != 0) {
              qp.close();
              return ret;
            }

            // query has run capture the time
            //统计指令的运行时间
            long end = System.currentTimeMillis();
            double timeTaken = (end - start) / 1000.0;

            ArrayList<String> res = new ArrayList<String>();
            //打印查询结果的列名称
            printHeader(qp, out);

            // print the results
            //打印查询结果
            int counter = 0;
            try {
              if (out instanceof FetchConverter) {
                ((FetchConverter)out).fetchStarted();
              }
              while (qp.getResults(res)) {
                for (String r : res) {
                  out.println(r);
                }
                counter += res.size();
                res.clear();
                if (out.checkError()) {
                  break;
                }
              }
            } catch (IOException e) {
              console.printError("Failed with exception " + e.getClass().getName() + ":"
                  + e.getMessage(), "\n"
                  + org.apache.hadoop.util.StringUtils.stringifyException(e));
              ret = 1;
            }
            //关闭结果
            int cret = qp.close();
            if (ret == 0) {
              ret = cret;
            }

            if (out instanceof FetchConverter) {
              ((FetchConverter)out).fetchFinished();
            }

            console.printInfo("Time taken: " + timeTaken + " seconds" +
                (counter == 0 ? "" : ", Fetched: " + counter + " row(s)"));
          } else {
            // 如果proc不是Driver，也就是用户执行的是非SQL查询操作，直接执行语句，
            // 不执行fetchResult的操作
            String firstToken = tokenizeCmd(cmd.trim())[0];
            String cmd_1 = getFirstCmd(cmd.trim(), firstToken.length());

            if (ss.getIsVerbose()) {
              ss.out.println(firstToken + " " + cmd_1);
            }
            CommandProcessorResponse res = proc.run(cmd_1);
            if (res.getResponseCode() != 0) {
              ss.out.println("Query returned non-zero code: " + res.getResponseCode() +
                  ", cause: " + res.getErrorMessage());
            }
            ret = res.getResponseCode();
          }
        }
      } catch (CommandNeedRetryException e) {
        //如果执行过程中出现异常，修改needRetry标志，下次循环是retry。
        console.printInfo("Retry query with a different approach...");
        tryCount++;
        needRetry = true;
      }
    } while (needRetry);

    return ret;
  }

  /**
   * If enabled and applicable to this command, print the field headers
   * for the output.
   *
   * @param qp Driver that executed the command
   * @param out PrintStream which to send output to
   * printHeader函数通过调用driver.getSchema.getFiledSchema，
   * 获取查询结果的列集合 ，然后依次打印出列名。
   */
  private void printHeader(Driver qp, PrintStream out) {
    List<FieldSchema> fieldSchemas = qp.getSchema().getFieldSchemas();
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_CLI_PRINT_HEADER)
          && fieldSchemas != null) {
      // Print the column names
      boolean first_col = true;
      for (FieldSchema fs : fieldSchemas) {
        if (!first_col) {
          out.print('\t');
        }
        out.print(fs.getName());
        first_col = false;
      }
      out.println();
    }
  }

  public int processLine(String line) {
    return processLine(line, false);
  }

  /**
   * Processes a line of semicolon separated commands
   *
   * @param line
   *          The commands to process
   * @param allowInterrupting
   *          When true the function will handle SIG_INT (Ctrl+C) by interrupting the processing and
   *          returning -1
   * @return 0 if ok
   */
  public int processLine(String line, boolean allowInterrupting) {
    SignalHandler oldSignal = null;
    Signal interruptSignal = null;

    if (allowInterrupting) {
      // Remember all threads that were running at the time we started line processing.
      // Hook up the custom Ctrl+C handler while processing this line
      interruptSignal = new Signal("INT");
      oldSignal = Signal.handle(interruptSignal, new SignalHandler() {
        private final Thread cliThread = Thread.currentThread();
        private boolean interruptRequested;

        @Override
        public void handle(Signal signal) {
          boolean initialRequest = !interruptRequested;
          interruptRequested = true;

          // Kill the VM on second ctrl+c
          if (!initialRequest) {
            console.printInfo("Exiting the JVM");
            System.exit(127);
          }

          // Interrupt the CLI thread to stop the current statement and return
          // to prompt
          console.printInfo("Interrupting... Be patient, this might take some time.");
          console.printInfo("Press Ctrl+C again to kill JVM");

          // First, kill any running MR jobs
          HadoopJobExecHelper.killRunningJobs();
          TezJobExecHelper.killRunningJobs();
          HiveInterruptUtils.interrupt();
        }
      });
    }

    try {
      int lastRet = 0, ret = 0;

      String command = "";
      for (String oneCmd : line.split(";")) {

        if (StringUtils.endsWith(oneCmd, "\\")) {
          command += StringUtils.chop(oneCmd) + ";";
          continue;
        } else {
          command += oneCmd;
        }
        if (StringUtils.isBlank(command)) {
          continue;
        }

        ret = processCmd(command);
        //wipe cli query state
        SessionState ss = SessionState.get();
        ss.setCommandType(null);
        command = "";
        lastRet = ret;
        boolean ignoreErrors = HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLIIGNOREERRORS);
        if (ret != 0 && !ignoreErrors) {
          CommandProcessorFactory.clean((HiveConf) conf);
          return ret;
        }
      }
      CommandProcessorFactory.clean((HiveConf) conf);
      return lastRet;
    } finally {
      // Once we are done processing the line, restore the old handler
      if (oldSignal != null && interruptSignal != null) {
        Signal.handle(interruptSignal, oldSignal);
      }
    }
  }

  public int processReader(BufferedReader r) throws IOException {
    String line;
    StringBuilder qsb = new StringBuilder();

    while ((line = r.readLine()) != null) {
      // Skipping through comments
      if (! line.startsWith("--")) {
        qsb.append(line + "\n");
      }
    }

    return (processLine(qsb.toString()));
  }

  public int processFile(String fileName) throws IOException {
    Path path = new Path(fileName);
    FileSystem fs;
    if (!path.toUri().isAbsolute()) {
      fs = FileSystem.getLocal(conf);
      path = fs.makeQualified(path);
    } else {
      fs = FileSystem.get(path.toUri(), conf);
    }
    BufferedReader bufferReader = null;
    int rc = 0;
    try {
      bufferReader = new BufferedReader(new InputStreamReader(fs.open(path)));
      rc = processReader(bufferReader);
    } finally {
      IOUtils.closeStream(bufferReader);
    }
    return rc;
  }

  public void processInitFiles(CliSessionState ss) throws IOException {
    boolean saveSilent = ss.getIsSilent();
    ss.setIsSilent(true);
    for (String initFile : ss.initFiles) {
      int rc = processFile(initFile);
      if (rc != 0) {
        System.exit(rc);
      }
    }
    if (ss.initFiles.size() == 0) {
      if (System.getenv("HIVE_HOME") != null) {
        String hivercDefault = System.getenv("HIVE_HOME") + File.separator +
          "bin" + File.separator + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          int rc = processFile(hivercDefault);
          if (rc != 0) {
            System.exit(rc);
          }
          console.printError("Putting the global hiverc in " +
                             "$HIVE_HOME/bin/.hiverc is deprecated. Please "+
                             "use $HIVE_CONF_DIR/.hiverc instead.");
        }
      }
      if (System.getenv("HIVE_CONF_DIR") != null) {
        String hivercDefault = System.getenv("HIVE_CONF_DIR") + File.separator
          + HIVERCFILE;
        if (new File(hivercDefault).exists()) {
          int rc = processFile(hivercDefault);
          if (rc != 0) {
            System.exit(rc);
          }
        }
      }
      if (System.getProperty("user.home") != null) {
        String hivercUser = System.getProperty("user.home") + File.separator +
          HIVERCFILE;
        if (new File(hivercUser).exists()) {
          int rc = processFile(hivercUser);
          if (rc != 0) {
            System.exit(rc);
          }
        }
      }
    }
    ss.setIsSilent(saveSilent);
  }

  public void processSelectDatabase(CliSessionState ss) throws IOException {
    String database = ss.database;
    if (database != null) {
      int rc = processLine("use " + database + ";");
      if (rc != 0) {
        System.exit(rc);
      }
    }
  }

  public static Completer[] getCommandCompleter() {
    // StringsCompleter matches against a pre-defined wordlist
    // We start with an empty wordlist and build it up
    List<String> candidateStrings = new ArrayList<String>();

    // We add Hive function names
    // For functions that aren't infix operators, we add an open
    // parenthesis at the end.
    for (String s : FunctionRegistry.getFunctionNames()) {
      if (s.matches("[a-z_]+")) {
        candidateStrings.add(s + "(");
      } else {
        candidateStrings.add(s);
      }
    }

    // We add Hive keywords, including lower-cased versions
    for (String s : HiveParser.getKeywords()) {
      candidateStrings.add(s);
      candidateStrings.add(s.toLowerCase());
    }

    StringsCompleter strCompleter = new StringsCompleter(candidateStrings);

    // Because we use parentheses in addition to whitespace
    // as a keyword delimiter, we need to define a new ArgumentDelimiter
    // that recognizes parenthesis as a delimiter.
    ArgumentDelimiter delim = new AbstractArgumentDelimiter() {
      @Override
      public boolean isDelimiterChar(CharSequence buffer, int pos) {
        char c = buffer.charAt(pos);
        return (Character.isWhitespace(c) || c == '(' || c == ')' ||
            c == '[' || c == ']');
      }
    };

    // The ArgumentCompletor allows us to match multiple tokens
    // in the same line.
    final ArgumentCompleter argCompleter = new ArgumentCompleter(delim, strCompleter);
    // By default ArgumentCompletor is in "strict" mode meaning
    // a token is only auto-completed if all prior tokens
    // match. We don't want that since there are valid tokens
    // that are not in our wordlist (eg. table and column names)
    argCompleter.setStrict(false);

    // ArgumentCompletor always adds a space after a matched token.
    // This is undesirable for function names because a space after
    // the opening parenthesis is unnecessary (and uncommon) in Hive.
    // We stack a custom Completor on top of our ArgumentCompletor
    // to reverse this.
    Completer customCompletor = new Completer () {
      @Override
      public int complete (String buffer, int offset, List completions) {
        List<String> comp = completions;
        int ret = argCompleter.complete(buffer, offset, completions);
        // ConsoleReader will do the substitution if and only if there
        // is exactly one valid completion, so we ignore other cases.
        if (completions.size() == 1) {
          if (comp.get(0).endsWith("( ")) {
            comp.set(0, comp.get(0).trim());
          }
        }
        return ret;
      }
    };

    List<String> vars = new ArrayList<String>();
    for (HiveConf.ConfVars conf : HiveConf.ConfVars.values()) {
      vars.add(conf.varname);
    }

    StringsCompleter confCompleter = new StringsCompleter(vars) {
      @Override
      public int complete(final String buffer, final int cursor, final List<CharSequence> clist) {
        int result = super.complete(buffer, cursor, clist);
        if (clist.isEmpty() && cursor > 1 && buffer.charAt(cursor - 1) == '=') {
          HiveConf.ConfVars var = HiveConf.getConfVars(buffer.substring(0, cursor - 1));
          if (var == null) {
            return result;
          }
          if (var.getValidator() instanceof Validator.StringSet) {
            Validator.StringSet validator = (Validator.StringSet)var.getValidator();
            clist.addAll(validator.getExpected());
          } else if (var.getValidator() != null) {
            clist.addAll(Arrays.asList(var.getValidator().toDescription(), ""));
          } else {
            clist.addAll(Arrays.asList("Expects " + var.typeString() + " type value", ""));
          }
          return cursor;
        }
        if (clist.size() > DELIMITED_CANDIDATE_THRESHOLD) {
          Set<CharSequence> delimited = new LinkedHashSet<CharSequence>();
          for (CharSequence candidate : clist) {
            Iterator<String> it = Splitter.on(".").split(
                candidate.subSequence(cursor, candidate.length())).iterator();
            if (it.hasNext()) {
              String next = it.next();
              if (next.isEmpty()) {
                next = ".";
              }
              candidate = buffer != null ? buffer.substring(0, cursor) + next : next;
            }
            delimited.add(candidate);
          }
          clist.clear();
          clist.addAll(delimited);
        }
        return result;
      }
    };

    StringsCompleter setCompleter = new StringsCompleter("set") {
      @Override
      public int complete(String buffer, int cursor, List<CharSequence> clist) {
        return buffer != null && buffer.equals("set") ? super.complete(buffer, cursor, clist) : -1;
      }
    };

    ArgumentCompleter propCompleter = new ArgumentCompleter(setCompleter, confCompleter) {
      @Override
      public int complete(String buffer, int offset, List<CharSequence> completions) {
        int ret = super.complete(buffer, offset, completions);
        if (completions.size() == 1) {
          completions.set(0, ((String)completions.get(0)).trim());
        }
        return ret;
      }
    };
    return new Completer[] {propCompleter, customCompletor};
  }

  //任务执行入口
  public static void main(String[] args) throws Exception {
    int ret = new CliDriver().run(args);
    System.exit(ret);
  }

  public  int run(String[] args) throws Exception {
    /**
     * 对输入的指令进行初步解析，提取-e -h hiveconf hivevar等参数信息，设置用户提供的系统和Hive环境变量。
     * 详细实现，参考OptionsProcessor类，不再详细描述。
     */
    OptionsProcessor oproc = new OptionsProcessor();
    if (!oproc.process_stage1(args)) {
      return 1;
    }

    // NOTE: It is critical(关键) to do this here so that log4j is reinitialized
    // before any of the other core hive classes are loaded
    // 初始化log4j日志组件
    boolean logInitFailed = false;
    String logInitDetailMessage;
    try {
      logInitDetailMessage = LogUtils.initHiveLog4j();
    } catch (LogInitializationException e) {
      logInitFailed = true;
      logInitDetailMessage = e.getMessage();
    }

    /**
     * 初始化HiveConf，并根据HiveConf实例化CliSessionState，设置输入输出流为标准控制台。
     * CliSessionState 继承了SessionState类，创建了一些记录用户输入的字符串，
     * 在实例化的过程中，主要是用来记录HiveConf，并生成一个会话ID，参见SessionState构造函数.
     */
    CliSessionState ss = new CliSessionState(new HiveConf(SessionState.class));
    ss.in = System.in;
    try {
      ss.out = new PrintStream(System.out, true, "UTF-8");
      ss.info = new PrintStream(System.err, true, "UTF-8");
      ss.err = new CachingPrintStream(System.err, true, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return 3;
    }

    /**
     * 根据stage1解析的参数内容，填充CliSessionState的字符串，
     * 比如用户输入了-e 则这个stage就把-e 对应的字符串赋值给CliSessionState的 execString成员。
     */
    if (!oproc.process_stage2(ss)) {
      return 2;
    }

    /**
     * 在允许打印输出的模式下，如果日志初始化失败，打印失败信息
     */
    if (!ss.getIsSilent()) {
      if (logInitFailed) {
        System.err.println(logInitDetailMessage);
      } else {
        SessionState.getConsole().printInfo(logInitDetailMessage);
      }
    }

    // set all properties specified via command line
    /**
     * 将用户命令行输入的配置信息和变量等，覆盖HiveConf的默认值
     */
    HiveConf conf = ss.getConf();
    for (Map.Entry<Object, Object> item : ss.cmdProperties.entrySet()) {
      conf.set((String) item.getKey(), (String) item.getValue());
      ss.getOverriddenConfigurations().put((String) item.getKey(), (String) item.getValue());
    }

    // read prompt configuration and substitute variables.
    prompt = conf.getVar(HiveConf.ConfVars.CLIPROMPT);
    prompt = new VariableSubstitution().substitute(conf, prompt);
    prompt2 = spacesForString(prompt);

    /**
     * 设置当前回话状态，执行CLI驱动
     */
    SessionState.start(ss);
    // execute cli driver work
    try {
      return executeDriver(ss, conf, oproc);
    } finally {
      ss.close();
    }
  }

  /**
   * Execute the cli work
   * @param ss CliSessionState of the CLI driver
   * @param conf HiveConf for the driver session
   * @param oproc Operation processor of the CLI invocation(调用)
   * @return status of the CLI command execution
   * @throws Exception
   * 在进入executeDriver之前，我们可以认为Hive处理的是用户进入Hive程序的指令，到此用户已经进入了Hive，
   * Cli的Driver将不断读取用户的HiveQL语句并解析，提交给Driver。
   * executeDriver函数内部出了根据用户参数做出的一些执行响应外，还设置了用户HiveQL的执行历史记录，
   * 也就是方便我们使用上下标键查看之前执行的指令的功能，不再详述。
   * executeDriver函数内部核心的代码是通过while循环不断按行读取用户的输入，然后调用ProcessLine拼接一条命令cmd，
   * 传递给processCmd处理用户输入。下面就来看看processCmd函数。
   */
  private int executeDriver(CliSessionState ss, HiveConf conf, OptionsProcessor oproc)
      throws Exception {

    CliDriver cli = new CliDriver();
    cli.setHiveVariables(oproc.getHiveVariables());

    // use the specified database if specified
    cli.processSelectDatabase(ss);

    // Execute -i init files (always in silent mode)
    cli.processInitFiles(ss);

    if (ss.execString != null) {
      int cmdProcessStatus = cli.processLine(ss.execString);
      return cmdProcessStatus;
    }

    try {
      if (ss.fileName != null) {
        return cli.processFile(ss.fileName);
      }
    } catch (FileNotFoundException e) {
      System.err.println("Could not open input file for reading. (" + e.getMessage() + ")");
      return 3;
    }

    setupConsoleReader();

    String line;
    int ret = 0;
    String prefix = "";
    String curDB = getFormattedDb(conf, ss);
    String curPrompt = prompt + curDB;
    String dbSpaces = spacesForString(curDB);

    while ((line = reader.readLine(curPrompt + "> ")) != null) {
      if (!prefix.equals("")) {
        prefix += '\n';
      }
      if (line.trim().endsWith(";") && !line.trim().endsWith("\\;")) {
        line = prefix + line;
        ret = cli.processLine(line, true);
        prefix = "";
        curDB = getFormattedDb(conf, ss);
        curPrompt = prompt + curDB;
        dbSpaces = dbSpaces.length() == curDB.length() ? dbSpaces : spacesForString(curDB);
      } else {
        prefix = prefix + line;
        curPrompt = prompt2 + dbSpaces;
        continue;
      }
    }

    return ret;
  }

  private void setupCmdHistory() {
    final String HISTORYFILE = ".hivehistory";
    String historyDirectory = System.getProperty("user.home");
    PersistentHistory history = null;
    try {
      if ((new File(historyDirectory)).exists()) {
        String historyFile = historyDirectory + File.separator + HISTORYFILE;
        history = new FileHistory(new File(historyFile));
        reader.setHistory(history);
      } else {
        System.err.println("WARNING: Directory for Hive history file: " + historyDirectory +
                           " does not exist.   History will not be available during this session.");
      }
    } catch (Exception e) {
      System.err.println("WARNING: Encountered an error while trying to initialize Hive's " +
                         "history file.  History will not be available during this session.");
      System.err.println(e.getMessage());
    }

    // add shutdown hook to flush the history to history file
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      @Override
      public void run() {
        History h = reader.getHistory();
        if (h instanceof FileHistory) {
          try {
            ((FileHistory) h).flush();
          } catch (IOException e) {
            System.err.println("WARNING: Failed to write command history file: " + e.getMessage());
          }
        }
      }
    }));
  }

  protected void setupConsoleReader() throws IOException {
    reader = new ConsoleReader();
    reader.setExpandEvents(false);
    reader.setBellEnabled(false);
    for (Completer completer : getCommandCompleter()) {
      reader.addCompleter(completer);
    }
    setupCmdHistory();
  }

  /**
   * Retrieve the current database name string to display, based on the
   * configuration value.
   * @param conf storing whether or not to show current db
   * @param ss CliSessionState to query for db name
   * @return String to show user for current db value
   */
  private static String getFormattedDb(HiveConf conf, CliSessionState ss) {
    if (!HiveConf.getBoolVar(conf, HiveConf.ConfVars.CLIPRINTCURRENTDB)) {
      return "";
    }
    //BUG: This will not work in remote mode - HIVE-5153
    String currDb = SessionState.get().getCurrentDatabase();

    if (currDb == null) {
      return "";
    }

    return " (" + currDb + ")";
  }

  /**
   * Generate a string of whitespace the same length as the parameter
   *
   * @param s String for which to generate equivalent whitespace
   * @return  Whitespace
   */
  private static String spacesForString(String s) {
    if (s == null || s.length() == 0) {
      return "";
    }
    return String.format("%1$-" + s.length() +"s", "");
  }

  public void setHiveVariables(Map<String, String> hiveVariables) {
    SessionState.get().setHiveVariables(hiveVariables);
  }

}
