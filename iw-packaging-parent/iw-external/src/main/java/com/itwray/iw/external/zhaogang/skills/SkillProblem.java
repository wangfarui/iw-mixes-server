package com.itwray.iw.external.zhaogang.skills;

final class SkillProblem extends RuntimeException {
    final int status;

    SkillProblem(int status, String message) {
        super(message);
        this.status = status;
    }

    static SkillProblem bad(String message) { return new SkillProblem(400, message); }
    static SkillProblem conflict(String message) { return new SkillProblem(409, message); }
    static SkillProblem missing() { return new SkillProblem(404, "Skill 或版本不存在"); }
}
