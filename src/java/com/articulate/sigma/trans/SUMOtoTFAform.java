package com.articulate.sigma.trans;

import com.articulate.sigma.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by apease on 7/23/18.
 */
public class SUMOtoTFAform {

    public static KB kb;

    private static boolean debug = true;

    private static HashMap<String,HashSet<String>> varmap = null;

    // a map of relation signatures (where function returns are index 0)
    // modified from the original by the constraints of the axiom
    private static HashMap<String,ArrayList<String>> signatures = null;

    public static boolean initialized = false;

    public static FormulaPreprocessor fp = new FormulaPreprocessor();

    // constraints on numeric types
    public static HashMap<String,String> numericConstraints = new HashMap();

    // variable names of constraints on numeric types
    public static HashMap<String,String> numericVars = new HashMap();

    /** *************************************************************
     */
    public static boolean isComparisonOperator(String s) {

        int under = s.lastIndexOf("__");
        if (under == -1)
            return Formula.isComparisonOperator(s);
        if (Formula.isComparisonOperator(s.substring(0,under)))
            return true;
        return false;
    }

    /** *************************************************************
     */
    public static boolean isMathFunction(String s) {

        int under = s.lastIndexOf("__");
        if (under == -1)
            return Formula.isMathFunction(s);
        if (Formula.isMathFunction(s.substring(0,under)))
            return true;
        return false;
    }

    /** *************************************************************
     */
    public static String withoutSuffix(String s) {

        if (StringUtil.emptyString(s))
            return s;
        int under = s.indexOf("__");
        if (under == -1)
            return s;
        return s.substring(0,under);
    }

    /** *************************************************************
     * Set the cached information of automatically generated functions
     * and relations needed to cover the polymorphic type signatures
     * of build-in TFF terms
     */
    public static void setNumericFunctionInfo() {

        //System.out.println("setNumericFunctionInfo()");
        if (kb.containsTerm("AdditionFn__IntegerFn")) // this routine has already been run or cached via serialization
            return;
        for (String s : Formula.COMPARISON_OPERATORS) {
            kb.kbCache.extendInstance(s,"Integer");
            kb.kbCache.extendInstance(s,"RealNumber");
            kb.kbCache.extendInstance(s,"RationalNumber");
        }
        for (String s : Formula.MATH_FUNCTIONS) {
            kb.kbCache.extendInstance(s,"IntegerFn");
            kb.kbCache.extendInstance(s,"RealNumberFn");
            kb.kbCache.extendInstance(s,"RationalNumberFn");
        }
    }

    /** *************************************************************
     * Fill the indicated elements with the empty string, starting at start and ending
     * at end-1
     */
    public static void fill (ArrayList<String> ar, int start, int end) {

        if (ar.size() <= end)
            for (int i = start; i < end; i++)
                ar.add("");
        else
            for (int i = start; i < end; i++)
                ar.set(i,"");
    }

    /** *************************************************************
     * If there's no such element index, fill the previous elements
     * with the empty string
     */
    public static void safeSet (ArrayList<String> ar, int index, String val) {

        if (index > ar.size()-1)
            fill(ar,ar.size(),index+1);
        ar.set(index,val);
    }

    /** *************************************************************
     * Extract modifications to the relation signature from annotations
     * embedded in the suffixes to its name
     */
    public static ArrayList<String> relationExtractSig(String rel) {

        if (StringUtil.emptyString(rel))
            return new ArrayList<String>();
        if (debug) System.out.println("relationExtractSig(): " + rel);
        ArrayList<String> origsig = kb.kbCache.getSignature(withoutSuffix(rel));
        if (debug) System.out.println("relationExtractSig(): origsig: " + origsig);
        if (origsig == null) {
            System.out.println("Error in relationExtractSig(): null signature for " + rel);
            return null;
        }
        ArrayList<String> sig = new ArrayList(origsig);
        String patternString = "(\\d)(In|Re|Ra)";

        int under = rel.indexOf("__");
        if (under == -1)
            return sig;
        String text = rel.substring(under + 2, rel.length());
        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
            String type = matcher.group(2);
            if (type.equals("In"))
                type = "Integer";
            else if (type.equals("Re"))
                type = "RealNumber";
            else if (type.equals("Ra"))
                type = "RationalNumber";
            else
                continue;
            int arg = Integer.parseInt(matcher.group(1));
            if (arg > sig.size()-1)
                fill(sig,sig.size(),arg+1);
            if (debug) System.out.println("relationExtractSig(): matches: " +
                    arg + ", " + matcher.group(2));
            safeSet(sig,arg,type);
        }
        if (debug) System.out.println("relationExtractSig(): for rel: " +
                rel + " set sig " + sig);
        return sig;
    }
    /** *************************************************************
     * Embed the type signature for TFF numeric types into the name of
     * the relation.  This is used for when a relation's signature is
     * modified from its authored original
     */
    private static String relationEmbedSig(String rel, ArrayList<String> sig) {

        String[] a = new String[] {"In","Re","Ra"};
        Collection typeChars = Arrays.asList(a);
        StringBuffer sb = new StringBuffer();
        sb.append(rel + "__");
        if (kb.isFunction(rel) && typeChars.contains(sig.get(0).substring(0,2))) {
            sb.append("0" + sig.get(0).substring(0, 2));
        }
        for (int i = 1; i < sig.size(); i++) {
            if (typeChars.contains(sig.get(i).substring(0,2)))
            sb.append(i + sig.get(i).substring(0, 2));
        }
        return sb.toString();
    }

    /** *************************************************************
     * Recurse through the formula giving numeric and comparison
     * operators a __Integer or __RealNumber suffix if they operate on
     * numbers.  TODO check the return types of any enclosed functions
     * since this only works now for literal numbers
     */
    public static Formula convertNumericFunctions(Formula f) {

        //System.out.println("convertNumericFunctions(): " + f);
        if (f == null)
            return f;
        if (f.atom()) {
            int ttype = f.theFormula.charAt(0);
            if (Character.isDigit(ttype))
                ttype = StreamTokenizer_s.TT_NUMBER;
            return f;
        }
        Formula car = f.carAsFormula();
        ArrayList<String> args = f.complexArgumentsToArrayList(1);
        if (Formula.isMathFunction(car.theFormula) ||
                (Formula.isComparisonOperator(car.theFormula) && !car.theFormula.equals("equals"))) {
            StringBuffer argsStr = new StringBuffer();
            boolean isInt = false;
            boolean isReal = false;
            boolean isRat = false;
            for (String s : args) {
                if (StringUtil.isInteger(s))
                    isReal = true; // isInt = true; it could be a real that just doesn't have a decimal
                if (!isInt && StringUtil.isNumeric(s))
                    isReal = true;
                if (!isInt && !isReal) {
                    Formula sf = new Formula(s);
                    String type = kb.kbCache.getRange(sf.car());
                    if (type != null && (type.equals("Integer") || kb.isSubclass(type,"Integer")))
                        isInt = true;
                    if (type != null && (type.equals("RationalNumber") || kb.isSubclass(type,"RationalNumber")))
                        isRat = true;
                    if (type != null && (type.equals("RealNumber") || kb.isSubclass(type,"RealNumber")))
                        isReal = true;
                    argsStr.append(convertNumericFunctions(sf) + " ");
                }
                else
                    argsStr.append(s + " ");
            }
            argsStr.deleteCharAt(argsStr.length()-1);
            String suffix = "";
            if (isInt)
                suffix = "__Integer";
            if (isRat)
                suffix = "__RationalNumber";
            if (isReal)
                suffix = "__RealNumber";
            if (suffix != "" && Formula.isMathFunction(car.theFormula))
                suffix = suffix + "Fn";
            f.theFormula = "(" + car.theFormula + suffix + " " + argsStr.toString() + ")";
        }
        else {
            StringBuffer argsStr = new StringBuffer();
            if (args != null) {
                for (String s : args) {
                    Formula sf = new Formula(s);
                    argsStr.append(convertNumericFunctions(sf) + " ");
                }
                argsStr.deleteCharAt(argsStr.length() - 1);
                f.theFormula = "(" + car.theFormula + " " + argsStr.toString() + ")";
            }
        }
        return f;
    }

    /** *************************************************************
     */
    private static String processQuant(Formula f, Formula car, String op,
                                       ArrayList<String> args) {

        //if (debug) System.out.println("processQuant(): quantifier");
        if (args.size() < 2) {
            System.out.println("Error in processQuant(): wrong number of arguments to " + op + " in " + f);
            return "";
        }
        else {
            //if (debug) System.out.println("processQuant(): correct # of args");
            if (args.get(0) != null) {
                //if (debug) System.out.println("processQuant(): valid varlist: " + args.get(0));
                Formula varlist = new Formula(args.get(0));
                ArrayList<String> vars = varlist.argumentsToArrayList(0);
                //if (debug) System.out.println("processRecurse(): valid vars: " + vars);
                StringBuffer varStr = new StringBuffer();
                for (String v : vars) {
                    String oneVar = SUMOformulaToTPTPformula.translateWord(v,v.charAt(0),false);
                    if (varmap.keySet().contains(v) && !StringUtil.emptyString(varmap.get(v))) {
                        String type = kb.mostSpecificTerm(varmap.get(v));
                        oneVar = oneVar + ":" + SUMOKBtoTFAKB.translateSort(kb,type);
                    }
                    varStr.append(oneVar + ", ");
                }
                //if (debug) System.out.println("processQuant(): valid vars: " + varStr);
                String opStr = " ! ";
                if (op.equals("exists"))
                    opStr = " ? ";
                //if (debug) System.out.println("processQuant(): quantified formula: " + args.get(1));
                return opStr + "[" + varStr.toString().substring(0,varStr.length()-2) + "] : (" +
                        processRecurse(new Formula(args.get(1))) + ")";
            }
            else {
                System.out.println("Error in processQuant(): null arguments to " + op + " in " + f);
                return "";
            }
        }
    }

    /** *************************************************************
     */
    private static String processLogOp(Formula f, Formula car,
                                        ArrayList<String> args) {
        String op = car.theFormula;
        //System.out.println("processRecurse(): op: " + op);
        //System.out.println("processRecurse(): args: " + args);
        if (op.equals("and")) {
            if (args.size() < 2) {
                System.out.println("Error in processLogOp(): wrong number of arguments to " + op + " in " + f);
                return "";
            }
            else
                return processRecurse(new Formula(args.get(0))) + " & " +
                        processRecurse(new Formula(args.get(1)));
        }
        if (op.equals("=>")) {
            if (args.size() < 2) {
                System.out.println("Error in processLogOp(): wrong number of arguments to " + op + " in " + f);
                return "";
            }
            else
                return processRecurse(new Formula(args.get(0))) + " => " +
                        processRecurse(new Formula(args.get(1)));
        }
        if (op.equals("<=>")) {
            if (args.size() < 2) {
                System.out.println("Error in processLogOp(): wrong number of arguments to " + op + " in " + f);
                return "";
            }
            else
                return "(" + processRecurse(new Formula(args.get(0))) + " => " +
                        processRecurse(new Formula(args.get(1))) + ") & (" +
                        processRecurse(new Formula(args.get(1))) + " => " +
                        processRecurse(new Formula(args.get(0))) + ")";
        }
        if (op.equals("or")) {
            if (args.size() < 2) {
                System.out.println("Error in processLogOp(): wrong number of arguments to " + op + " in " + f);
                return "";
            }
            else
                return processRecurse(new Formula(args.get(0))) + " | " +
                        processRecurse(new Formula(args.get(1)));
        }
        if (op.equals("not")) {
            if (args.size() != 1) {
                System.out.println("Error in processLogOp(): wrong number of arguments to " + op + " in " + f);
                return "";
            }
            else
                return "~" + processRecurse(new Formula(args.get(0)));
        }
        if (op.equals("forall") || op.equals("exists"))
            return processQuant(f,car,op,args);
        System.out.println("Error in processLogOp(): bad logical operator " + op + " in " + f);
        return "";
    }

    /** *************************************************************
     */
    private static String processCompOp(Formula f, Formula car,
                                       ArrayList<String> args) {

        String op = car.theFormula;
        if (args.size() != 2) {
            System.out.println("Error in processCompOp(): wrong number of arguments to " + op + " in " + f);
            return "";
        }
        if (debug) System.out.println("processCompOp(): op: " + op);
        if (debug) System.out.println("processCompOp(): args: " + args);
        if (op.startsWith("equal")) {
            return processRecurse(new Formula(args.get(0))) + " = " +
                    processRecurse(new Formula(args.get(1)));
        }
        if (op.startsWith("greaterThanOrEqualTo"))
            return "$greatereq(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        if (op.startsWith("greaterThan"))
            return "$greater(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        if (op.startsWith("lessThanOrEqualTo"))
            return "$lesseq(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        if (op.startsWith("lessThan"))
            return "$less(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";

        System.out.println("Error in processCompOp(): bad comparison operator " + op + " in " + f);
        return "";
    }

    /** *************************************************************
     */
    private static String processMathOp(Formula f, Formula car,
                                        ArrayList<String> args) {

        String op = car.theFormula;
        if (args.size() != 2) {
            System.out.println("Error in processMathOp(): wrong number of arguments to " + op + " in " + f);
            return "";
        }
        if (debug) System.out.println("processMathOp(): op: " + op);
        if (debug) System.out.println("processMathOp(): args: " + args);
        if (op.startsWith("AdditionFn"))
            return "$sum(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        if (op.startsWith("SubtractionFn"))
            return "$difference(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        if (op.startsWith("MultiplicationFn"))
            return "$product(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        if (op.startsWith("DivisionFn"))
            return "$quotient_e(" + processRecurse(new Formula(args.get(0))) + " ," +
                    processRecurse(new Formula(args.get(1))) + ")";
        System.out.println("Error in processMathOp(): bad math operator " + op + " in " + f);
        return "";
    }

    /** *************************************************************
     */
    public static String processRecurse(Formula f) {

        if (debug) System.out.println("processRecurse(): " + f);
        if (debug) System.out.println("processRecurse(): varmap: " + varmap);
        if (f == null)
            return "";
        if (f.atom()) {
            int ttype = f.theFormula.charAt(0);
            if (Character.isDigit(ttype))
                ttype = StreamTokenizer_s.TT_NUMBER;
            return SUMOformulaToTPTPformula.translateWord(f.theFormula,ttype,false);
        }
        Formula car = f.carAsFormula();
        //System.out.println("processRecurse(): car: " + car);
        //System.out.println("processRecurse(): car: " + car.theFormula);
        ArrayList<String> args = f.complexArgumentsToArrayList(1);
        if (car.listP()) {
            System.out.println("Error in processRecurse(): formula " + f);
            return "";
        }
        if (Formula.isLogicalOperator(car.theFormula))
            return processLogOp(f,car,args);
        else if (isComparisonOperator(car.theFormula))
            return processCompOp(f,car,args);
        else if (isMathFunction(car.theFormula))
            return processMathOp(f,car,args);
        else {
            //if (debug) System.out.println("processRecurse(): not math or comparison op: " + car);
            StringBuffer argStr = new StringBuffer();
            for (String s : args)
                argStr.append(processRecurse(new Formula(s)) + ", ");
            String result = "s__" + car.theFormula + "(" + argStr.substring(0,argStr.length()-2) + ")";
            //if (debug) System.out.println("processRecurse(): result: " + result);
            return result;
        }
    }

    /** *************************************************************
     * result is a side effect on varmap
     */
    private static HashMap<String,HashSet<String>> cloneVarmap() {

        HashMap<String,HashSet<String>> newVarmap = new HashMap<>();
        for (String s : varmap.keySet()) {
            HashSet<String> newSet = new HashSet<>();
            newSet.addAll(varmap.get(s));
            newVarmap.put(s,newSet);
        }
        return newVarmap;
    }

    /** *************************************************************
     * check if t is one of the fundamental types of $int, $rat, $real
     * or SUMO types that are subtypes of Integer, RationalNumber or
     * RealNumber
     */
    private static boolean fundamentalSubtype(String t, String sigType) {

        if (sigType.equals("Integer") && kb.isSubclass(t,"Integer"))
            return true;
        if (sigType.equals("RealNumber") && kb.isSubclass(t,"RealNumber"))
            return true;
        if (sigType.equals("RationalNumber") && kb.isSubclass(t,"RationalNumber"))
            return true;
        return false;
    }

    /** *************************************************************
     * @param t is the type of the actual argument to op
     * @param sigType is the type required for this argument to op
     * @param op is the relation
     */
    private static String numberSuffix(String op, String t, String sigType) {

        if (t.equals(sigType))
            return "";
        if (fundamentalSubtype(t,sigType))
            return "";
        if (debug) System.out.println("numberSuffix(): op,t,type: " +
                op + ", " + t + ", " + sigType);
        if (debug) System.out.println("numberSuffix(): kb.isSubclass(t, sigType): " +
                kb.isSubclass(t, sigType));
        if (debug) System.out.println("numberSuffix(): kb.isSubclass(t, \"Number\"): " +
                kb.isSubclass(t, "Number"));
        String suffix = "";
        String fn = "";
        if (kb.isFunction(op))
            fn = "Fn";
        if (kb.isSubclass(t, sigType) && kb.isSubclass(t, "Number")) {
            if (kb.isSubclass(t, "RealNumber") || t.equals("RealNumber"))
                suffix = "__RealNumber" + fn;
            if (kb.isSubclass(t, "Integer") || t.equals("Integer"))
                suffix = "__Integer" + fn;
        }
        if (debug) System.out.println("numberSuffix(): suffix: " +
                suffix);
        return suffix;
    }

    /** *************************************************************
     * Find the types of each argument.  If a variable, look up in
     * varmap.  If a function, check its return type.
     */
    private static ArrayList<String> collectArgTypes(ArrayList<String> args) {

        if (debug) System.out.println("collectArgTypes(): varmap: " + varmap);
        ArrayList<String> types = new ArrayList<String>();
        for (String s : args) {
            if (Formula.isVariable(s)) {
                String vtype = kb.mostSpecificTerm(varmap.get(s));
                if (!StringUtil.emptyString(vtype))
                    types.add(vtype);
            }
            else if (Formula.listP(s)) {
                if (kb.isFunctional(s)) {
                    String op = (new Formula(s)).car();
                    String range = kb.kbCache.getRange(op);
                    if (!StringUtil.emptyString(range))
                        types.add(range);
                }
            }
            else if (kb.isInstance(s)) {
                HashSet<String> p = kb.immediateParents(s);
                String most = kb.mostSpecificTerm(p);
                if (!StringUtil.emptyString(most))
                    types.add(most);
            }
        }
        return types;
    }

    /** *************************************************************
     */
    private static String getOpType(String op) {

        String type = "";
        int i = op.indexOf("__");
        if (i != -1) {
            type = op.substring(i+2,op.length());
            if (type.endsWith("Fn"))
                type = type.substring(0,type.length()-2);
            return type;
        }
        ArrayList<String> sig = kb.kbCache.getSignature(op);
        return kb.mostSpecificTerm(sig);
    }

    /** *************************************************************
     */
    private static void constrainVars(String type, ArrayList<String> args) {

        if (!kb.isSubclass(type,"Quantity"))
            return;
        if (debug) System.out.println("constrainVars(): " + type);
        for (String t : args) {
            if (!Formula.isVariable(t))
                continue;
            HashSet<String> types = varmap.get(t);
            if (debug) System.out.println("constrainVars(): checking var " + t + " with type " + types);
            String lowest = kb.mostSpecificTerm(types);
            if (debug) System.out.println("constrainVars(): type " + type + " lowest " + lowest);
            if (lowest == null || kb.termDepth(type) > kb.termDepth(lowest)) {  // classes lower in hierarchy have a large number (of levels)
                if (lowest == null)
                    types = new HashSet<>();
                types.add(type);
                if (debug) System.out.println("constrainVars(): constraining " + t + " to " + type);
            }
        }
    }

    /** *************************************************************
     * @param args is a list of arguments of formula f, starting with
     *             an empty first argument for the relation
     * @param op   is the operator of formula f
     */
    private static String constrainOp(Formula f, String op, ArrayList<String> args) {

        if (debug) System.out.println("constrainOp(): op: " + op);
        if (debug) System.out.println("constrainOp(): f: " + f);
        if (debug) System.out.println("constrainOp(): args: " + args);
        String suffix = "";
        ArrayList<String> sig = kb.kbCache.getSignature(op);

        String opType = getOpType(op);
        ArrayList<String> argtypes = collectArgTypes(args);

        String lowest = kb.mostSpecificTerm(argtypes);
        if (debug) System.out.println("constrainOp(): op type: " + opType);
        if (debug) System.out.println("constrainOp(): arg types: " + argtypes);
        if (debug) System.out.println("constrainOp(): most specific arg type: " + lowest);
        if (kb.isSubclass(lowest,opType)) {
            constrainVars(lowest,args); // side effect on varmap
        }
        if (sig == null) {
            System.out.println("Error in constrainOp(): null signature for " + op);
        }
        else {
            for (int i = 1; i < args.size(); i++) {
                String arg = args.get(i);
                if (i >= sig.size()) {
                    System.out.println("Error in constrainOp(): missing signature element for " +
                            op + "  in form " + f);
                    continue;
                }
                String type = sig.get(i);
                if (Formula.listP(arg)) {
                    String justArg = (new Formula(arg)).car();
                    if (kb.isFunction(justArg)) { // ||
                            // (justArg.indexOf('_') != -1 && kb.isFunction(justArg.substring(0, justArg.indexOf('_'))))) {
                        String t = kb.kbCache.getRange(justArg);
                        if (StringUtil.emptyString(t))
                            System.out.println("Error in constrainOp(): empty function range for " + justArg);
                        suffix = numberSuffix(op, t, type);
                    }
                    if (Formula.isVariable(justArg)) {
                        String t = kb.mostSpecificTerm(varmap.get(justArg));
                        if (StringUtil.emptyString(t))
                            System.out.println("Error in constrainOp(): empty variable type for " + justArg);
                        suffix = numberSuffix(op, t, type);
                    }
                    args.set(i, constrainFunctVarsRecurse(new Formula(arg)));
                }
                else {
                    if (Formula.isVariable(arg)) {
                        String t = kb.mostSpecificTerm(varmap.get(arg));
                        if (StringUtil.emptyString(t))
                            System.out.println("Error in constrainOp(): empty variable type for " + arg);
                        suffix = numberSuffix(op, t, type);
                    }
                    if (StringUtil.isNumeric(arg)) { // even without a decimal, we can't be sure it's Integer
                        suffix = numberSuffix(op, "RealNumber", type);
                    }
                }
            }
        }
        if (op.indexOf("_") != -1 && suffix != "") {
            if (op.endsWith("__RealNumber") || op.endsWith("__Integer") || op.endsWith("RationalNumber") ||
                    op.endsWith("__RealNumberFn") || op.endsWith("__IntegerFn") || op.endsWith("RationalNumberFn"))
                op = op.substring(0,op.lastIndexOf("__"));
        }
        ArrayList<String> newargs = new ArrayList<>();
        newargs.addAll(args);
        newargs.remove(0);
        String result = "(" + op + suffix + " " + StringUtil.arrayListToSpacedString(newargs) + ")";
        if (debug) System.out.println("constrainOp(): result: " + result);
        return result;
    }

    /** *************************************************************
     */
    private static String constrainFunctVarsRecurse(Formula f) {

        if (debug) System.out.println("constrainFunctVarsRecurse(): " + f);
        if (f == null) return "";
        if (f.atom()) return f.theFormula;
        Formula car = f.carAsFormula();
        //System.out.println("processRecurse(): car: " + car);
        //System.out.println("processRecurse(): car: " + car.theFormula);
        ArrayList<String> args = f.complexArgumentsToArrayList(0);
        if (car.listP()) {
            System.out.println("Error in constrainFunctVarsRecurse(): formula " + f);
            return "";
        }
        String op = car.theFormula;
        if (debug) System.out.println("constrainFunctVarsRecurse(): op: " + op);
        //ArrayList<String> sig = kb.kbCache.getSignature(op);
//        if ((car.theFormula.equals(Formula.EQUAL)) || isComparisonOperator(car.theFormula) ||
  //              isMathFunction(car.theFormula))
        if (!Formula.isLogicalOperator(op) && !Formula.isVariable(op))
            return constrainOp(f,op,args);
        else {
            StringBuffer resultString = new StringBuffer();
            resultString.append("(" + op);
            if (debug) System.out.println("constrainFunctVarsRecurse(): not math or comparison op: " + car);
            ArrayList<String> newargs = new ArrayList<>();
            newargs.addAll(args);
            newargs.remove(0);
            for (String s : newargs)
                resultString.append(" " + constrainFunctVarsRecurse(new Formula(s)));
            resultString.append(")");
            return resultString.toString();
        }
    }

    /** *************************************************************
     * Only constrain the element of the varmap if the new type is more specific
     * @result is the new varmap
     */
    private static void constrainTypeRestriction(HashMap<String,HashSet<String>> newvarmap) {

        for (String k : newvarmap.keySet()) {
            HashSet<String> newvartypes = newvarmap.get(k);
            String newt = kb.mostSpecificTerm(newvartypes);
            HashSet<String> oldvartypes = varmap.get(k);
            String oldt = kb.mostSpecificTerm(oldvartypes);
            //System.out.println("constrainTypeRestriction(): newt, oldt: " +
            //        newt + ", " + oldt);
            if (StringUtil.emptyString(newt) && StringUtil.emptyString(oldt)) {
                System.out.println("Error in constrainTypeRestriction(): empty variables: " +
                        newt + " " + oldt);
                System.out.println("Error in constrainTypeRestriction(): newvarmap: " +
                        newvarmap);
                Thread.dumpStack();
                return;
            }
            if (StringUtil.emptyString(newt))
                varmap.put(k,oldvartypes);
            else if (StringUtil.emptyString(oldt))
                varmap.put(k,newvartypes);
            else if (kb.isSubclass(newt,oldt))
                varmap.put(k,newvartypes);
            else
                varmap.put(k,oldvartypes);
        }
    }

    /** *************************************************************
     * result is a side effect on varmap and the formula
     */
    private static void constrainFunctVars(Formula f) {

        int counter = 0;
        if (debug) System.out.println("constrainFunctVars(): formula: " + f);
        HashMap<String,HashSet<String>> oldVarmap = null;
        do {
            counter++;
            oldVarmap = cloneVarmap();
            String newf = constrainFunctVarsRecurse(f);
            f.theFormula = newf;
            HashMap<String,HashSet<String>> types = fp.findAllTypeRestrictions(f, kb);
            if (debug) System.out.println("constrainFunctVars(): found types: " + types);
            constrainTypeRestriction(types);
            //System.out.println("constrainFunctVars(): new varmap: " + varmap);
            //System.out.println("constrainFunctVars(): old varmap: " + oldVarmap);
        } while (!varmap.equals(oldVarmap)  && counter < 5);
    }

    /** *************************************************************
     * Recursive routine to eliminate 'and' and 'or' with one or zero
     * arguments
     */
    public static String elimUnitaryLogops(Formula f) {

        //System.out.println("elimUnitaryLogops(): f: " + f);
        if (f.empty() || f.atom()) {
            //System.out.println("elimUnitaryLogops(): atomic result: " + f.theFormula);
            return f.theFormula;
        }
        ArrayList<String> args = f.complexArgumentsToArrayList(0);
        //System.out.println("elimUnitaryLogops(): args: " + args);
        //System.out.println("elimUnitaryLogops(): size: " + args.size());
        //System.out.println("elimUnitaryLogops(): car: " + f.car());
        if (f.car().equals("and") || f.car().equals("or")) {
            if (args.size() == 1) {
                //System.out.println("elimUnitaryLogops(): empty result: ");
                return "";
            }
            if (args.size() == 2) {
                //System.out.println("elimUnitaryLogops(): elimination: " + args.get(1));
                String result = elimUnitaryLogops(new Formula(args.get(1)));
                //System.out.println("elimUnitaryLogops(): result: " + result);
                return result;
            }
        }
        //System.out.println("elimUnitaryLogops(): not an elimination ");
        StringBuffer result = new StringBuffer();
        result = result.append("(" + args.get(0));
        for (int i = 1; i < args.size(); i++) {
            result.append(" " + elimUnitaryLogops(new Formula(args.get(i))));
            //System.out.println("elimUnitaryLogops(): appending: " + result);
        }
        result.append(")");
        //System.out.println("elimUnitaryLogops(): result: " + result);
        return result.toString();
    }

    /** *************************************************************
     * This is the primary method of the class.  It takes a SUO-KIF
     * formula and returns a TFF formula
     */
    public static String process(Formula f) {

        if (debug) System.out.println("\nprocess(): =======================");
        f.theFormula = modifyPrecond(f);
        f.theFormula = modifyTypesToConstraints(f);
        f.theFormula = elimUnitaryLogops(f); // remove empty (and... and (or...
        f.theFormula = convertNumericFunctions(f).theFormula;
        HashMap<String,HashSet<String>> varDomainTypes = fp.computeVariableTypes(f, kb);
        if (debug) System.out.println("process: varDomainTypes " + varDomainTypes);
        // get variable types which are explicitly defined in formula
        HashMap<String,HashSet<String>> varExplicitTypes = fp.findExplicitTypesClassesInAntecedent(kb,f);
        if (debug) System.out.println("process: varExplicitTypes " + varExplicitTypes);
        //varmap = fp.findTypeRestrictions(f, kb);
        varmap = fp.findAllTypeRestrictions(f, kb);
        if (debug) System.out.println("process(): formula: " + f);
        if (debug) System.out.println("process(): varmap: " + varmap);
        String oldf = f.theFormula;
        int counter = 0;
        do {
            counter++;
            oldf = f.theFormula;
            constrainFunctVars(f);
        } while (!f.theFormula.equals(oldf) && counter < 5);
        if (f != null && f.listP()) {
            ArrayList<String> UqVars = f.collectUnquantifiedVariables();
            if (debug) System.out.println("process(): unquant: " + UqVars);
            String result = processRecurse(f);
            if (debug) System.out.println("process(): result 1: " + result);
            StringBuffer qlist = new StringBuffer();
            for (String s : UqVars) {
                if (debug) System.out.println("process(): s: " + s);
                String t = "";
                String oneVar = SUMOformulaToTPTPformula.translateWord(s,s.charAt(0),false);
                if (varmap.keySet().contains(s) && !StringUtil.emptyString(varmap.get(s))) {
                    t = kb.mostSpecificTerm(varmap.get(s));
                    if (debug) System.out.println("process(): varmap.get(s): " + varmap.get(s));
                    if (debug) System.out.println("process(): t: " + t);
                    if (t != null)
                        qlist.append(oneVar + " : " + SUMOKBtoTFAKB.translateSort(kb,t) + ",");
                }
            }
            if (qlist.length() > 1) {
                qlist.deleteCharAt(qlist.length() - 1);  // delete final comma
                result = "! [" + qlist + "] : (" + result + ")";
            }
            if (debug) System.out.println("process(): result 2: " + result);
            return result;
        }
        return ("");
    }

    /** *************************************************************
     */
    public static String process(String s) {

        if (StringUtil.emptyString(s))
            return "";
        Formula f = new Formula(s);
        return process(f);
    }

    /** *************************************************************
     */
    public static Collection<String> processList(Collection<Formula> l) {

        ArrayList<String> result = new ArrayList<>();
        for (Formula f : l)
            result.add(process(f));
        return result;
    }

    /** *************************************************************
     * if the precondition of a rule is of the form (instance ?X term)
     * @return the name of the variable in the instance statement
     * (without the leading question mark)
     */
    private static String matchingPrecond(Formula f, String term) {

        String ant = FormulaUtil.antecedent(f);
        //System.out.println("matchingPrecond(): term: " + term);
        //System.out.println("matchingPrecond(): ant: " + ant);
        if (ant == null)
            return null;
        Pattern p = Pattern.compile("\\(instance \\?(\\w+) " + term + "\\)");
        Matcher m = p.matcher(ant);
        if (m.find()) {
            //System.out.println("matchingPrecond(): matches! ");
            String var = m.group(1);
            return var;
        }
        return null;
    }

    /** *************************************************************
     * if all or part of a consequent of a rule is of the form (instance ?X term)
     * @return the name of the type in the instance statement
     */
    private static String matchingInstanceTerm(Formula f) {

        //String cons = FormulaUtil.consequent(f);
        //System.out.println("matchingPostcondTerm(): const: " + cons);
        if (f.theFormula == null)
            return null;
        Pattern p = Pattern.compile("\\(instance \\?\\w+ (\\w+)\\)");
        Matcher m = p.matcher(f.theFormula);
        if (m.find()) {
            //System.out.println("matchingPostcondTerm(): matches! ");
            String type = m.group(1);
            return type;
        }
        return null;
    }

    /** *************************************************************
     * if all or part of a consequent of a rule is of the form (instance ?X term)
     * @return the name of the type in the instance statement
     */
    private static String matchingInstance(Formula f) {

        HashSet<String> intChildren = kb.kbCache.getChildClasses("Integer");
        //System.out.println("buildNumericConstraints(): int: " + intChildren);
        HashSet<String> realChildren = new HashSet<String>();
        realChildren.addAll(kb.kbCache.getChildClasses("RealNumber"));
        if (realChildren.contains("Integer"))
            realChildren.remove("Integer");
        //System.out.println("buildNumericConstraints(): real: " + realChildren);
        String type = matchingInstanceTerm(f);
        if (intChildren.contains(type))
            return type;
        else if (realChildren.contains(type))
            return type;
        else return null;
    }

    /** *************************************************************
     * remove statements of the form (instance ?X term) if 'term' is
     * Integer or RealNumber and ?X is already of that type in the
     * quantifier list for the formula
     * @return the modified formula
     */
    protected static String modifyPrecond(Formula f) {

        if (f == null)
            return f.theFormula;
        String type = "Integer";
        Pattern p = Pattern.compile("\\(instance \\?(\\w+) " + type + "\\)");
        Matcher m = p.matcher(f.theFormula);
        if (m.find()) {
            String var = m.group(1);
            f.theFormula = m.replaceAll("");
        }

        type = "RealNumber";
        p = Pattern.compile("\\(instance \\?(\\w+) " + type + "\\)");
        m = p.matcher(f.theFormula);
        if (m.find()) {
            String var = m.group(1);
            f.theFormula = m.replaceAll("");
        }

        return f.theFormula;
    }

    /** *************************************************************
     * replace type statements of the form (instance ?X term), where
     * term is a subtype of Integer or RealNumber with a constraint
     * that defines that type
     * @return String version of the modified formula
     */
    protected static String modifyTypesToConstraints(Formula f) {

        String type = null;
        boolean found = false;
        do {
            type = matchingInstance(f);
            if (type == null)
                return f.theFormula;
            Pattern p = Pattern.compile("\\(instance \\?(\\w+) " + type + "\\)");
            Matcher m = p.matcher(f.theFormula);
            if (m.find()) {
                found = true;
                String var = m.group(1);
                String toReplace = "(instance ?" + var + " " + type + ")";
                String cons = numericConstraints.get(type);
                if (StringUtil.emptyString(cons))
                    System.out.println("Error in modifyTypesToConstraints(): no constraint for " + type);
                String origVar = numericVars.get(type);
                String newCons = cons.replace("?" + origVar, "?" + var);
                if (debug) System.out.println("modifyTypesToConstraints(): replacing " +
                    toReplace + " with " + newCons);
                f.theFormula = f.theFormula.replace(toReplace, newCons);
            }
            else
                found = false;
        } while (type != null && found);
        return f.theFormula;
    }

    /** *************************************************************
     * Since SUMO has subtypes of numbers but TFF doesn't allow
     * subtypes, we need to capture all the rules that say things
     * like non negative integers are greater than 0 so they
     * can be added to axioms with NonNegativeInteger, replacing that
     * class with $int but adding the constraint that it must be
     * greater than 0
     */
    private static void buildNumericConstraints() {

        HashSet<String> intChildren = kb.kbCache.getChildClasses("Integer");
        //System.out.println("buildNumericConstraints(): int: " + intChildren);
        HashSet<String> realChildren = new HashSet<String>();
        realChildren.addAll(kb.kbCache.getChildClasses("RealNumber"));
        if (realChildren.contains("Integer"))
            realChildren.remove("Integer");
        //System.out.println("buildNumericConstraints(): real: " + realChildren);
        //HashSet<Formula> intForms = new HashSet<>();
        for (String t : intChildren) {
            //System.out.println("buildNumericConstraints(): t: " + t);
            ArrayList<Formula> intFormsTemp = kb.ask("ant",0,t);
            if (intFormsTemp != null) {
                for (Formula f : intFormsTemp) {
                    String var = matchingPrecond(f,t);
                    if (var != null) {
                        numericConstraints.put(t, FormulaUtil.consequent(f));
                        numericVars.put(t,var);
                    }
                }
            }
        }
        //HashSet<Formula> realForms = new HashSet<>();
        for (String t : realChildren) {
            //System.out.println("buildNumericConstraints(): t: " + t);
            ArrayList<Formula> realFormsTemp = kb.ask("ant", 0, t);
            if (realFormsTemp != null) {
                for (Formula f : realFormsTemp) {
                    String var = matchingPrecond(f,t);
                    if (var != null) {
                        numericConstraints.put(t, FormulaUtil.consequent(f));
                        numericVars.put(t,var);
                    }
                }
            }
        }
    }

    /** *************************************************************
     */
    public static void initOnce() {

        if (initialized)
            return;
        KBmanager.getMgr().initializeOnce();
        kb = KBmanager.getMgr().getKB("SUMO");
        fp = new FormulaPreprocessor();
        fp.addTypes = false;
        buildNumericConstraints();
        initialized = true;
    }

    /** *************************************************************
     */
    public static void test1() {

        Formula f = new Formula("(equal ?X (AdditionFn__IntegerFn 1 2))");
        System.out.println("SUMOtoTFAform.test1(): " + processRecurse(f));
        f = new Formula("(equal ?X (SubtractionFn__IntegerFn 2 1))");
        System.out.println("SUMOtoTFAform.test1(): " + processRecurse(f));
    }

    /** *************************************************************
     */
    public static void test2() {

        Formula f = new Formula("(=> (and (equal (AbsoluteValueFn ?NUMBER1) ?NUMBER2) " +
                "(instance ?NUMBER1 RealNumber) (instance ?NUMBER2 RealNumber)) " +
                "(or (and (instance ?NUMBER1 NonnegativeRealNumber) (equal ?NUMBER1 ?NUMBER2)) " +
                "(and (instance ?NUMBER1 NegativeRealNumber) (equal ?NUMBER2 (SubtractionFn 0 ?NUMBER1)))))");
        System.out.println("SUMOtoTFAform.test2(): " + process(f));
    }

    /** *************************************************************
     */
    public static void test3() {

        Formula f = new Formula("(<=> (equal (RemainderFn ?NUMBER1 ?NUMBER2) ?NUMBER) " +
                "(equal (AdditionFn (MultiplicationFn (FloorFn (DivisionFn ?NUMBER1 ?NUMBER2)) ?NUMBER2) ?NUMBER) ?NUMBER1))");
        System.out.println("SUMOtoTFAform.test3(): " + process(f));
    }

    /** *************************************************************
     */
    public static void test4() {

        Formula f = new Formula("(<=> (greaterThanOrEqualTo ?NUMBER1 ?NUMBER2) (or (equal ?NUMBER1 ?NUMBER2) (greaterThan ?NUMBER1 ?NUMBER2)))");
        System.out.println("SUMOtoTFAform.test4(): " + process(f));
    }

    /** *************************************************************
     */
    public static void test5() {

        Formula f = new Formula("(=>\n" +
                "(measure ?QUAKE\n" +
                "(MeasureFn ?VALUE RichterMagnitude))\n" +
                "(instance ?VALUE PositiveRealNumber))");
        System.out.println("SUMOtoTFAform.test5(): " + modifyTypesToConstraints(f));
    }

    /** *************************************************************
     */
    public static void test6() {

        Formula f = new Formula("(<=> " +
                "(equal (RemainderFn ?NUMBER1 ?NUMBER2) ?NUMBER) " +
                "(equal (AdditionFn (MultiplicationFn (FloorFn (DivisionFn ?NUMBER1 ?NUMBER2)) ?NUMBER2) ?NUMBER) ?NUMBER1))");
        System.out.println("SUMOtoTFAform.test6(): " + process(f));
        System.out.println("expect: ");
        System.out.println("tff(kb_SUMO_73,axiom,(! [V__NUMBER1 : $int,V__NUMBER2 : $int,V__NUMBER : $int] : " +
                "((s__RemainderFn(V__NUMBER1, V__NUMBER2) = V__NUMBER " +
                "=> $sum($product(s__FloorFn($quotient_e(V__NUMBER1 ,V__NUMBER2)) ,V__NUMBER2) ,V__NUMBER) = " +
                "V__NUMBER1) & " +
                "($sum($product(s__FloorFn($quotient_e(V__NUMBER1 ,V__NUMBER2)) ,V__NUMBER2) ,V__NUMBER) = " +
                "V__NUMBER1 => s__RemainderFn(V__NUMBER1, V__NUMBER2) = V__NUMBER)))).");
    }

    /** *************************************************************
     */
    public static void test7() {

        Formula f = new Formula("(<=> (and (equal (AbsoluteValueFn ?NUMBER1) ?NUMBER2) " +
                "(instance ?NUMBER1 RealNumber) (instance ?NUMBER2 RealNumber)) " +
                "(or (and (instance ?NUMBER1 NonnegativeRealNumber) (equal ?NUMBER1 ?NUMBER2)) " +
                "(and (instance ?NUMBER1 NegativeRealNumber) (equal ?NUMBER2 (SubtractionFn 0 ?NUMBER1)))))");
        System.out.println("SUMOtoTFAform.test7(): " + process(f));
        System.out.println("test7() expected: tff(kb_SUMO_1,axiom,(! [V__NUMBER1 : $int,V__NUMBER2 : $int] : " +
                "((s__AbsoluteValueFn__Integer(V__NUMBER1) = V__NUMBER2 => s__SignumFn__Integer(V__NUMBER1) = 1 " +
                "| s__SignumFn__Integer(V__NUMBER1) = 0 & V__NUMBER1 = V__NUMBER2 | $less(V__NUMBER1, 0) " +
                "& V__NUMBER2 = $difference(0 ,V__NUMBER1)) & (s__SignumFn__Integer(V__NUMBER1) = 1 | " +
                "s__SignumFn__Integer(V__NUMBER1) = 0 & V__NUMBER1 = V__NUMBER2 | $greater(V__NUMBER1, 0) & " +
                "V__NUMBER2 = $difference(0 ,V__NUMBER1) => s__AbsoluteValueFn__Integer(V__NUMBER1) = " +
                "V__NUMBER2 & $greater(V__NUMBER1, 0))))).");
    }

    /** *************************************************************
     */
    public static void test8() {

        Formula f = new Formula("(<=> (equal (LastFn ?LIST) ?ITEM) (exists (?NUMBER) " +
                "(and (equal (ListLengthFn ?LIST) ?NUMBER) " +
                "(equal (ListOrderFn ?LIST ?NUMBER) ?ITEM))))");
        System.out.println("SUMOtoTFAform.test8(): " + process(f));
        System.out.println("test8() expected: tff(kb_SUMO_138,axiom,(! [V__LIST : $int,V__ITEM : $i] : " +
                "((s__LastFn(V__LIST) = V__ITEM =>  ? [V__NUMBER:$int] : " +
                "(s__ListLengthFn(V__LIST) = V__NUMBER & s__ListOrderFn(V__LIST, V__NUMBER) = V__ITEM)) & " +
                "( ? [V__NUMBER:$int] : " +
                "(s__ListLengthFn(V__LIST) = V__NUMBER & s__ListOrderFn(V__LIST, V__NUMBER) = V__ITEM) => " +
                "s__LastFn(V__LIST) = V__ITEM)))).");
    }

    /** *************************************************************
     */
    public static void testRelEmbed() {

        String rel = "AbsoluteValueFn";
        ArrayList<String> sig = kb.kbCache.getSignature(rel);
        System.out.println("testRlEmbed(): " + sig);
        System.out.println("testRlEmbed(): new name: " + relationEmbedSig(rel,sig));
        kb.kbCache.extendInstance(rel,"1Re");
        kb.kbCache.signatures.put(rel + "__" + "1Re",sig);
    }

    /** *************************************************************
     */
    public static void testRelExtract() {

        String rel = "AbsoluteValueFn__1Re";
        System.out.println("testRelExtract(): new name: " + relationExtractSig(rel));
    }

    /** *************************************************************
     */
    public static void main(String[] args) {

        //debug = true;
        initOnce();
        setNumericFunctionInfo();
        System.out.println(numericConstraints);
        System.out.println(numericVars);
        //testRelEmbed();
        //testRelExtract();
        /*
        HashSet<String> realChildren = kb.kbCache.getChildClasses("RealNumber");
        System.out.println("main(): children of RealNumber: " + realChildren);

        realChildren = kb.kbCache.getChildClasses("PositiveRealNumber");
        System.out.println("main(): children of PositiveRealNumber: " + realChildren);

        realChildren = kb.kbCache.getChildClasses("NonnegativeRealNumber");
        System.out.println("main(): children of NonnegativeRealNumber: " + realChildren);
        */
        test7();
    }
}
