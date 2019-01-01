package main;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.DebugGL4;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import oglutils.OGLUtils;
import oglutils.ShaderUtils;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Random;

public class RendererMax implements GLEventListener {

    private final static int ITEM_SIZE = 4; // integer has 4 bytes

    private int computeProgram;
    private int locColumnsCount;

    private int shrinkProgram;
    private int locOriginalColumnsCount, locShrinkColumnsCount;

    private int[] locBuffer;

    private final int groupSize = 2; // limit 1536 -> 39

    private int origColumnsCount = 8;
    private int groupCount = origColumnsCount / groupSize;
    private int originalDataSize = origColumnsCount * origColumnsCount;
    private final IntBuffer data = IntBuffer.allocate(originalDataSize);
    private final IntBuffer dataOut = Buffers.newDirectIntBuffer(originalDataSize);

    private int shrinkColumnCount = groupCount;
    private int shrinkDataSize = shrinkColumnCount * shrinkColumnCount;

    private final boolean PRINT = false;

    @Override
    public void init(GLAutoDrawable glDrawable) {
        // check if shaders are supported
        OGLUtils.shaderCheck(glDrawable.getGL().getGL4());
        if ((OGLUtils.getVersionGLSL(glDrawable.getGL().getGL4()) < ShaderUtils.COMPUTE_SHADER_SUPPORT_VERSION)
                && (!OGLUtils.getExtensions(glDrawable.getGL().getGL4()).contains("compute_shader"))) {
            System.err.println("Compute shader is not supported");
            System.exit(0);
        }

        glDrawable.setGL(new DebugGL4(glDrawable.getGL().getGL4()));
        GL4 gl = glDrawable.getGL().getGL4();

        // get limits on work group size per dimension
        for (int dim = 0; dim < 3; dim++) {
            IntBuffer val = IntBuffer.allocate(1);
            gl.glGetIntegeri_v(GL4.GL_MAX_COMPUTE_WORK_GROUP_SIZE, dim, val);
            System.out.println("GL_MAX_COMPUTE_WORK_GROUP_SIZE [" + dim + "]: " + val.get(0));
        }

        {
            // get limit on work group size (sum on all dimensions)
            LongBuffer val = LongBuffer.allocate(1);
            gl.glGetInteger64v(GL4.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, val);
            System.out.println("GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS: " + val.get(0));
        }

        // get limit on work group count per dimension
        for (int dim = 0; dim < 3; dim++) {
            IntBuffer val = IntBuffer.allocate(1);
            gl.glGetIntegeri_v(GL4.GL_MAX_COMPUTE_WORK_GROUP_COUNT, dim, val);
            System.out.println("GL_MAX_COMPUTE_WORK_GROUP_COUNT [" + dim + "]: " + val.get(0));
        }

        // load programs
        computeProgram = ShaderUtils.loadProgram(gl, "/computeMax");
        shrinkProgram = ShaderUtils.loadProgram(gl, "/shrink");

        // load uniforms
        locColumnsCount = gl.glGetUniformLocation(computeProgram, "columnsCount");
        locOriginalColumnsCount = gl.glGetUniformLocation(shrinkProgram, "originalColumnsCount");
        locShrinkColumnsCount = gl.glGetUniformLocation(shrinkProgram, "shrinkColumnsCount");

        // buffer initialization
        long time = System.currentTimeMillis();
        data.rewind();
        Random r = new Random();
        for (int i = 0; i < originalDataSize; i++) {
            data.put(i, r.nextInt(100));
        }
        data.put(9, 100);
        System.out.println("Buffer initialization took " + (System.currentTimeMillis() - time) + " ms.");

        if (PRINT) {
            System.out.println("Input values");
            print(originalDataSize, origColumnsCount, groupCount, data);
        }

        // declare and generate a buffer object name
        locBuffer = new int[2];
        gl.glGenBuffers(2, locBuffer, 0);

        System.out.println("glBufferData...");
        // bind the buffer and define its initial storage capacity
        gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[0]);
        gl.glBufferData(GL4.GL_SHADER_STORAGE_BUFFER, ITEM_SIZE * originalDataSize, data, GL4.GL_STATIC_DRAW);

        gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[1]);
        gl.glBufferData(GL4.GL_SHADER_STORAGE_BUFFER, ITEM_SIZE * originalDataSize, dataOut, GL4.GL_STATIC_DRAW);
        System.out.println("DONE");

        // unbind the buffer
        gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, 0);

        System.out.println("glShaderStorageBlockBinding...");
        // assign the index of shader storage block to the binding point (see shader)
        gl.glShaderStorageBlockBinding(computeProgram, 0, 0); //input buffer
        gl.glShaderStorageBlockBinding(computeProgram, 1, 1); //output buffer

        gl.glShaderStorageBlockBinding(shrinkProgram, 0, 0); //shrink buffer
        gl.glShaderStorageBlockBinding(shrinkProgram, 1, 1); //shrink buffer
        System.out.println("DONE");

//        long time = System.currentTimeMillis();
//        IntBuffer testData = IntBuffer.allocate(400000000);
//        System.out.println(System.currentTimeMillis() - time);
//
//        time = System.currentTimeMillis();
//        IntBuffer testData2 = Buffers.newDirectIntBuffer(testData.array(), 0, 100000000);
//        System.out.println(System.currentTimeMillis() - time);
    }

    private void print(final int dataSize, final int columnsCount, final int groupCount, final IntBuffer data) {
        String dashes = new String(new char[(groupSize * 4 + 1) * groupCount]).replace("\0", "-");
        for (int i = 0; i < dataSize; i++) {
            if (i > 0 && i % columnsCount == 0) {
                System.out.println();
            }
            System.out.print(String.format("%02d, ", data.get(i)));
            if ((i + 1) % groupSize == 0 && (i + 1) % columnsCount != 0) {
                System.out.print("| ");
            }
            if ((i + 1) % (columnsCount * groupSize) == 0) {
                System.out.println();
                System.out.print(dashes);
            }
        }
        System.out.println();
        System.out.println();
    }

    @Override
    public void display(GLAutoDrawable glDrawable) {
        GL4 gl = glDrawable.getGL().getGL4();

        IntBuffer timesBuffer = IntBuffer.allocate(3);
        gl.glGenQueries(3, timesBuffer);
        gl.glQueryCounter(timesBuffer.get(0), GL4.GL_TIMESTAMP);

// GL_TIME_ELAPSED test
//        IntBuffer timesBuffer2 = IntBuffer.allocate(1);
//        gl.glGenQueries(1, timesBuffer2);
//        gl.glBeginQuery(GL4.GL_TIME_ELAPSED, timesBuffer2.get(0));
//        gl.glEndQuery(GL4.GL_TIME_ELAPSED);
//        IntBuffer intBuffer = IntBuffer.allocate(1);
//        gl.glGetQueryObjectiv(timesBuffer2.get(0), GL4.GL_QUERY_RESULT, intBuffer);
//        System.out.println("Time elapsed: " + intBuffer.get(0));
//        https://stackoverflow.com/questions/24446207/what-is-the-difference-between-querying-time-elapsed-in-opengl-with-gl-time-elap

//        https://www.khronos.org/registry/OpenGL/extensions/ARB/ARB_pipeline_statistics_query.txt
        IntBuffer invocationsQueryId = IntBuffer.allocate(1);
        gl.glGenQueries(1, invocationsQueryId);
        gl.glBeginQuery(GL4.GL_COMPUTE_SHADER_INVOCATIONS_ARB, invocationsQueryId.get(0));

        if (shrinkColumnCount > 0) {
            gl.glUseProgram(computeProgram);

            gl.glUniform1i(locColumnsCount, origColumnsCount);

            //set input and output buffer
            gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[0]);
            gl.glBindBufferBase(GL4.GL_SHADER_STORAGE_BUFFER, 0, locBuffer[0]);

            gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[1]);
            gl.glBindBufferBase(GL4.GL_SHADER_STORAGE_BUFFER, 1, locBuffer[1]);

            // dispatch compute, query counter
            System.out.println("Calling dispatch compute with " + groupCount + "^2 group" + (groupCount > 1 ? "s" : "") + ".");
            gl.glQueryCounter(timesBuffer.get(1), GL4.GL_TIMESTAMP);
            gl.glDispatchCompute(groupCount, groupCount, 1);
            gl.glQueryCounter(timesBuffer.get(2), GL4.GL_TIMESTAMP);

            getAndShowTime(gl, timesBuffer);

            // get compute shader invocations count
            gl.glEndQuery(GL4.GL_COMPUTE_SHADER_INVOCATIONS_ARB);
            IntBuffer invocationsCount = IntBuffer.allocate(1);
            gl.glGetQueryObjectiv(invocationsQueryId.get(0), GL4.GL_QUERY_RESULT, invocationsCount);
            System.out.println("Compute shader invocations: " + invocationsCount.get(0));
            System.out.println();

            if (PRINT) {
                // make sure writing to image has finished before read
                gl.glMemoryBarrier(GL4.GL_SHADER_STORAGE_BARRIER_BIT);
                gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[1]);
                gl.glGetBufferSubData(GL4.GL_SHADER_STORAGE_BUFFER, 0, ITEM_SIZE * originalDataSize, dataOut);

                System.out.println("Output values");
                dataOut.rewind();
                print(originalDataSize, origColumnsCount, groupCount, dataOut);
            }

            // second step
            // shrink data
            gl.glUseProgram(shrinkProgram);

            gl.glUniform1i(locShrinkColumnsCount, shrinkColumnCount);
            gl.glUniform1i(locOriginalColumnsCount, origColumnsCount);

            // bind input and output buffer
            gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[1]);
            gl.glBindBufferBase(GL4.GL_SHADER_STORAGE_BUFFER, 0, locBuffer[1]);

            gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[0]);
            gl.glBindBufferBase(GL4.GL_SHADER_STORAGE_BUFFER, 1, locBuffer[0]);

            gl.glDispatchCompute(groupCount, groupCount, 1);

            if (PRINT) {
                gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[0]);
                gl.glGetBufferSubData(GL4.GL_SHADER_STORAGE_BUFFER, 0, ITEM_SIZE * shrinkDataSize, dataOut);
                System.out.println("Shrinked values");
                dataOut.rewind();
                print(shrinkDataSize, shrinkColumnCount, groupCount / groupSize, dataOut);
            }

            originalDataSize /= Math.pow(groupSize, 2);
            origColumnsCount /= groupSize;
            shrinkDataSize /= Math.pow(groupSize, 2);
            shrinkColumnCount /= groupSize;

            groupCount /= groupSize;

            if (shrinkColumnCount == 0) {
                gl.glBindBuffer(GL4.GL_SHADER_STORAGE_BUFFER, locBuffer[0]);
                gl.glGetBufferSubData(GL4.GL_SHADER_STORAGE_BUFFER, 0, ITEM_SIZE, dataOut);
                System.out.println("Final value");
                System.out.println(dataOut.get(0));
            }
        } else {
            dispose(glDrawable);
            System.exit(0);
        }
    }

    private void getAndShowTime(GL4 gl, IntBuffer timesBuffer) {

        IntBuffer stopTimerAvailable = IntBuffer.allocate(1);
        stopTimerAvailable.put(0, 0);
        while (stopTimerAvailable.get(0) == 0) {
            gl.glGetQueryObjectiv(timesBuffer.get(2), GL4.GL_QUERY_RESULT_AVAILABLE, stopTimerAvailable);
        }
        LongBuffer time1 = LongBuffer.allocate(1);
        LongBuffer time2 = LongBuffer.allocate(1);
        LongBuffer time3 = LongBuffer.allocate(1);
        gl.glGetQueryObjectui64v(timesBuffer.get(0), GL4.GL_QUERY_RESULT, time1);
        gl.glGetQueryObjectui64v(timesBuffer.get(1), GL4.GL_QUERY_RESULT, time2);
        gl.glGetQueryObjectui64v(timesBuffer.get(2), GL4.GL_QUERY_RESULT, time3);

        //System.out.println(String.format("Time spent on the GPU 2-1 (bind): %f ms", (time2.get(0) - time1.get(0)) / 1000000.0));
        System.out.println(String.format("Time spent on the GPU 3-2 (dispatch): %f ms", (time3.get(0) - time2.get(0)) / 1000000.0));
        //System.out.println(String.format("Time spent on the GPU 3-1 (all): %f ms", (time3.get(0) - time1.get(0)) / 1000000.0));
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
    }

    @Override
    public void dispose(GLAutoDrawable glDrawable) {
        GL4 gl = glDrawable.getGL().getGL4();
        gl.glDeleteProgram(computeProgram);
        gl.glDeleteProgram(shrinkProgram);
        gl.glDeleteBuffers(2, locBuffer, 0);
    }

}