/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.core.file.impl;

import org.vertx.java.core.AsyncResultHandler;
import org.vertx.java.core.FutureResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.VoidResult;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.file.AsyncFile;
import org.vertx.java.core.file.FileSystemException;
import org.vertx.java.core.impl.BlockingAction;
import org.vertx.java.core.impl.Context;
import org.vertx.java.core.impl.VertxInternal;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;
import org.vertx.java.core.streams.ReadStream;
import org.vertx.java.core.streams.WriteStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class DefaultAsyncFile implements AsyncFile {

  private static final Logger log = LoggerFactory.getLogger(AsyncFile.class);
  public static final int BUFFER_SIZE = 8192;

  private final VertxInternal vertx;
  private final AsynchronousFileChannel ch;
  private final Context context;
  private boolean closed;
  private ReadStream readStream;
  private WriteStream writeStream;
  private Runnable closedDeferred;
  private long writesOutstanding;

  DefaultAsyncFile(final VertxInternal vertx, final String path, String perms, final boolean read, final boolean write, final boolean createNew,
            final boolean flush, final Context context) {
    if (!read && !write) {
      throw new FileSystemException("Cannot open file for neither reading nor writing");
    }
    this.vertx = vertx;
    Path file = Paths.get(path);
    HashSet<OpenOption> options = new HashSet<>();
    if (read) options.add(StandardOpenOption.READ);
    if (write) options.add(StandardOpenOption.WRITE);
    if (createNew) options.add(StandardOpenOption.CREATE);
    if (flush) options.add(StandardOpenOption.DSYNC);
    try {
      if (perms != null) {
        FileAttribute<?> attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(perms));
        ch = AsynchronousFileChannel.open(file, options, vertx.getBackgroundPool(), attrs);
      } else {
        ch = AsynchronousFileChannel.open(file, options, vertx.getBackgroundPool());
      }
    } catch (IOException e) {
      throw new FileSystemException(e);
    }
    this.context = context;
  }

  @Override
  public void close() {
    closeInternal(null);
  }

  @Override
  public void close(AsyncResultHandler<Void> handler) {
    closeInternal(handler);
  }

  @Override
  public AsyncFile write(Buffer buffer, int position, AsyncResultHandler<Void> handler) {
    check();
    ByteBuffer bb = buffer.getByteBuf().nioBuffer();
    doWrite(bb, position, handler);
    return this;
  }

  @Override
  public AsyncFile read(Buffer buffer, int offset, int position, int length, AsyncResultHandler<Buffer> handler) {
    check();
    ByteBuffer bb = ByteBuffer.allocate(length);
    doRead(buffer, offset, bb, position, handler);
    return this;
  }

  @Override
  public WriteStream writeStream() {
    check();
    if (writeStream == null) {
      writeStream = new WriteStream<WriteStream>() {
        Handler<Exception> exceptionHandler;
        Handler<Void> drainHandler;

        int pos;
        int maxWrites = 128 * 1024;    // TODO - we should tune this for best performance
        int lwm = maxWrites / 2;

        public WriteStream write(Buffer buffer) {
          check();
          final int length = buffer.length();
          ByteBuffer bb = buffer.getByteBuf().nioBuffer();

          doWrite(bb, pos, new AsyncResultHandler<Void>() {

            public void handle(FutureResult<Void> deferred) {
              if (deferred.succeeded()) {
                checkContext();
                checkDrained();
                if (writesOutstanding == 0 && closedDeferred != null) {
                  closedDeferred.run();
                }
              } else {
                handleException(deferred.cause());
              }
            }
          });
          pos += length;
          return this;
        }

        private void checkDrained() {
          if (drainHandler != null && writesOutstanding <= lwm) {
            Handler<Void> handler = drainHandler;
            drainHandler = null;
            handler.handle(null);
          }
        }

        public WriteStream setWriteQueueMaxSize(int maxSize) {
          check();
          this.maxWrites = maxSize;
          this.lwm = maxWrites / 2;
          return this;
        }

        public boolean writeQueueFull() {
          check();
          return writesOutstanding >= maxWrites;
        }

        public WriteStream drainHandler(Handler<Void> handler) {
          check();
          this.drainHandler = handler;
          checkDrained();
          return this;
        }

        public WriteStream exceptionHandler(Handler<Exception> handler) {
          check();
          this.exceptionHandler = handler;
          return this;
        }

        void handleException(Throwable t) {
          if (exceptionHandler != null && t instanceof Exception) {
            exceptionHandler.handle((Exception)t);
          } else {
            log.error("Unhandled exception", t);
          }
        }
      };
    }
    return writeStream;
  }

  @Override
  public ReadStream readStream() {
    check();
    if (readStream == null) {
      readStream = new ReadStream<ReadStream>() {

        boolean paused;
        Handler<Buffer> dataHandler;
        Handler<Exception> exceptionHandler;
        Handler<Void> endHandler;
        int pos;
        boolean readInProgress;

        void doRead() {
          if (!readInProgress) {
            readInProgress = true;
            Buffer buff = new Buffer(BUFFER_SIZE);
            read(buff, 0, pos, BUFFER_SIZE, new AsyncResultHandler<Buffer>() {

              public void handle(FutureResult<Buffer> ar) {
                if (ar.succeeded()) {
                  readInProgress = false;
                  Buffer buffer = ar.result();
                  if (buffer.length() == 0) {
                    // Empty buffer represents end of file
                    handleEnd();
                  } else {
                    pos += buffer.length();
                    handleData(buffer);
                    if (!paused && dataHandler != null) {
                      doRead();
                    }
                  }
                } else {
                  handleException(ar.cause());
                }
              }
            });
          }
        }

        public ReadStream dataHandler(Handler<Buffer> handler) {
          check();
          this.dataHandler = handler;
          if (dataHandler != null && !paused && !closed) {
            doRead();
          }
          return this;
        }

        public ReadStream exceptionHandler(Handler<Exception> handler) {
          check();
          this.exceptionHandler = handler;
          return this;
        }

        public ReadStream endHandler(Handler<Void> handler) {
          check();
          this.endHandler = handler;
          return this;
        }

        public ReadStream pause() {
          check();
          paused = true;
          return this;
        }

        public ReadStream resume() {
          check();
          if (paused && !closed) {
            paused = false;
            if (dataHandler != null) {
              doRead();
            }
          }
          return this;
        }

        void handleException(Throwable t) {
          if (exceptionHandler != null && t instanceof Exception) {
            checkContext();
            exceptionHandler.handle((Exception)t);
          } else {
            log.error("Unhandled exception", t);
          }
        }

        void handleData(Buffer buffer) {
          if (dataHandler != null) {
            checkContext();
            dataHandler.handle(buffer);
          }
        }

        void handleEnd() {
          if (endHandler != null) {
            checkContext();
            endHandler.handle(null);
          }
        }
      };
    }
    return readStream;
  }

  @Override
  public AsyncFile flush() {
    doFlush(null);
    return this;
  }

  @Override
  public AsyncFile flush(AsyncResultHandler<Void> handler) {
    doFlush(handler);
    return this;
  }

  private void doFlush(AsyncResultHandler<Void> handler) {
    checkClosed();
    checkContext();
    new BlockingAction<Void>(vertx, handler) {
      public Void action() {
        try {
          ch.force(false);
          return null;
        } catch (IOException e) {
          throw new FileSystemException(e);
        }
      }
    }.run();
  }

  private void doWrite(final ByteBuffer buff, final int position, final AsyncResultHandler<Void> handler) {
    writesOutstanding += buff.limit();
    writeInternal(buff, position, handler);
  }

  private void writeInternal(final ByteBuffer buff, final int position, final AsyncResultHandler<Void> handler) {

    ch.write(buff, position, null, new java.nio.channels.CompletionHandler<Integer, Object>() {

      public void completed(Integer bytesWritten, Object attachment) {

        int pos = position;

        if (buff.hasRemaining()) {
          // partial write
          pos += bytesWritten;
          // resubmit
          writeInternal(buff, pos, handler);
        } else {
          // It's been fully written
          context.execute(new Runnable() {
            public void run() {
              writesOutstanding -= buff.limit();
              handler.handle(new VoidResult().setResult());
            }
          });
        }
      }

      public void failed(Throwable exc, Object attachment) {
        if (exc instanceof Exception) {
          final Exception e = (Exception) exc;
          context.execute(new Runnable() {
            public void run() {
              handler.handle(new VoidResult().setResult());
            }
          });
        } else {
          log.error("Error occurred", exc);
        }
      }
    });
  }

  private void doRead(final Buffer writeBuff, final int offset, final ByteBuffer buff, final int position, final AsyncResultHandler<Buffer> handler) {

    ch.read(buff, position, null, new java.nio.channels.CompletionHandler<Integer, Object>() {

      int pos = position;

      final FutureResult<Buffer> result = new FutureResult<>();

      private void done() {
        context.execute(new Runnable() {
          public void run() {
            buff.flip();
            writeBuff.setBytes(offset, buff);
            result.setResult(writeBuff).setHandler(handler);
          }
        });
      }

      public void completed(Integer bytesRead, Object attachment) {
        if (bytesRead == -1) {
          //End of file
          done();
        } else if (buff.hasRemaining()) {
          // partial read
          pos += bytesRead;
          // resubmit
          doRead(writeBuff, offset, buff, pos, handler);
        } else {
          // It's been fully written
          done();
        }
      }

      public void failed(Throwable exc, Object attachment) {
        if (exc instanceof Exception) {
          final Exception e = (Exception) exc;
          context.execute(new Runnable() {
            public void run() {
              result.setFailure(e).setHandler(handler);
            }
          });
        } else {
          vertx.reportException(exc);
        }
      }
    });
  }

  private void check() {
    checkClosed();
    checkContext();
  }

  private void checkClosed() {
    if (closed) {
      throw new IllegalStateException("File handle is closed");
    }
  }

  private void checkContext() {
    if (!vertx.getContext().equals(context)) {
      throw new IllegalStateException("AsyncFile must only be used in the context that created it, expected: "
          + context + " actual " + vertx.getContext());
    }
  }

  private void doClose(AsyncResultHandler<Void> handler) {
    FutureResult<Void> res = new FutureResult<>();
    try {
      ch.close();
      res.setResult(null);
    } catch (IOException e) {
      res.setFailure(e);
    }
    if (handler != null) {
      handler.handle(res);
    }
  }

  private void closeInternal(final AsyncResultHandler<Void> handler) {
    check();

    closed = true;

    if (writesOutstanding == 0) {
      doClose(handler);
    } else {
      closedDeferred = new Runnable() {
        public void run() {
          doClose(handler);
        }
      };
    }
  }

}
