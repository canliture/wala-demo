package top.anemone.walademo;


import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.Descriptor;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.util.strings.Atom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.anemone.walademo.callGraph.CallGraphTestUtil;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

public class SimpleSlice {

    private static final Logger LOG = LoggerFactory.getLogger(PDFSDG.class);

    public static void main(String[] args) throws CancelException, WalaException, IOException {

        Slicer.DataDependenceOptions dOptions=Slicer.DataDependenceOptions.NO_BASE_NO_HEAP;
        Slicer.ControlDependenceOptions cOptions=Slicer.ControlDependenceOptions.FULL;
        doSlicing("./example_jar/wala-target-1.0-SNAPSHOT.jar",
                "top.anemone.walatarget.Main#sink",
                "println",
                dOptions,
                cOptions);
    }
    public static void doSlicing(String appJar, String caller, String callee,
                                 Slicer.DataDependenceOptions dOptions,
                                 Slicer.ControlDependenceOptions cOptions)
            throws WalaException, CancelException, IOException {
        // create an analysis scope representing the appJar as a J2SE application
        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(appJar,
                (new FileProvider()).getFile(CallGraphTestUtil.REGRESSION_EXCLUSIONS));
        ClassHierarchy cha = ClassHierarchyFactory.make(scope);

        String source="Ltop/anemone/walatarget/Main"; //entrypoint
        Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha, source);
        AnalysisOptions options = new AnalysisOptions(scope, entrypoints);

        // build the call graph
        com.ibm.wala.ipa.callgraph.CallGraphBuilder cgb = Util.makeVanillaZeroOneCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha, scope, null, null);
        CallGraph cg = cgb.makeCallGraph(options, null);
        PointerAnalysis pa = cgb.getPointerAnalysis();

        // find caller method
        String[] callerMethod=caller.split("#");
        String clazz=callerMethod[0].replace('.','/');
        Atom method=Atom.findOrCreateUnicodeAtom(callerMethod[1]);
        CGNode callerNode=null;
        for(CGNode n: cg){
            if (n.getMethod().getReference().getDeclaringClass().getName().toString().endsWith(clazz) && n.getMethod().getName().equals(method)) {
                callerNode=n;
                break;
            }
        }
        if(callerNode==null){
            Assertions.UNREACHABLE("failed to find method");
        }

        // find callee statement
        Statement calleeStmt=null;
        IR callerIR = callerNode.getIR();
        for (SSAInstruction s: Iterator2Iterable.make(callerIR.iterateAllInstructions())){
            if (s instanceof com.ibm.wala.ssa.SSAAbstractInvokeInstruction) {
                com.ibm.wala.ssa.SSAAbstractInvokeInstruction call = (com.ibm.wala.ssa.SSAAbstractInvokeInstruction) s;
                if (call.getCallSite().getDeclaredTarget().getName().toString().equals(callee)) {
                    com.ibm.wala.util.intset.IntSet indices = callerIR.getCallInstructionIndices(call.getCallSite());
                    com.ibm.wala.util.debug.Assertions.productionAssertion(indices.size() == 1, "expected 1 but got " + indices.size());
                    calleeStmt=new com.ibm.wala.ipa.slicer.NormalStatement(callerNode, indices.intIterator().next());
                }
            }
        }
        if(calleeStmt==null){
            Assertions.UNREACHABLE("failed to find call to " + callee + " in " + callerNode);
        }

        // context-sensitive traditional slice
        Collection<Statement> slice = Slicer.computeBackwardSlice(calleeStmt, cg, pa, dOptions, cOptions);
        for (Statement s : slice) {
            System.out.println(s);
        }
    }
}