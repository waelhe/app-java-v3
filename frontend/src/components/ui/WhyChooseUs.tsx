'use client';

import React from 'react';
import { ShieldCheck, Heart, Globe, Zap } from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';
import { motion } from 'framer-motion';

export default function WhyChooseUs() {
  const { language } = useLanguage();

  const features = [
    {
      icon: ShieldCheck,
      title: language === 'ar' ? 'حماية مالية كاملة' : 'Full Financial Protection',
      description: language === 'ar' 
        ? 'نظام Escrow يضمن بقاء أموالك في أمان حتى تكتمل الخدمة بنجاح.' 
        : 'Escrow system ensures your money stays safe until the service is successfully completed.',
      color: 'bg-blue-50 text-blue-600'
    },
    {
      icon: Heart,
      title: language === 'ar' ? 'دعم محلي حقيقي' : 'Real Local Support',
      description: language === 'ar' 
        ? 'فريقنا متواجد على الأرض لمساعدتك في أي وقت خلال رحلتك.' 
        : 'Our team is on the ground to help you at any time during your trip.',
      color: 'bg-rose-50 text-rose-600'
    },
    {
      icon: Globe,
      title: language === 'ar' ? 'تغطية شاملة' : 'Comprehensive Coverage',
      description: language === 'ar' 
        ? 'من الإقامة إلى الخدمات الطبية والتسوق، كل ما تحتاجه في مكان واحد.' 
        : 'From accommodation to medical services and shopping, everything you need in one place.',
      color: 'bg-emerald-50 text-emerald-600'
    },
    {
      icon: Zap,
      title: language === 'ar' ? 'ذكاء اصطناعي متطور' : 'Advanced AI',
      description: language === 'ar' 
        ? 'نستخدم أحدث تقنيات الذكاء الاصطناعي لتخصيص تجربتك وتوفير أفضل التوصيات.' 
        : 'We use the latest AI technologies to personalize your experience and provide the best recommendations.',
      color: 'bg-amber-50 text-amber-600'
    }
  ];

  return (
    <section className="py-20 bg-white">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div className="text-center max-w-3xl mx-auto mb-16">
          <motion.span 
            initial={{ opacity: 0, y: 10 }}
            whileInView={{ opacity: 1, y: 0 }}
            className="text-emerald-600 font-bold tracking-widest uppercase text-sm"
          >
            {language === 'ar' ? 'لماذا ضيف؟' : 'Why Dayf?'}
          </motion.span>
          <motion.h2 
            initial={{ opacity: 0, y: 10 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.1 }}
            className="text-3xl md:text-5xl font-black text-gray-900 mt-4 mb-6"
          >
            {language === 'ar' ? 'نعيد تعريف تجربة السفر والخدمات في سوريا' : 'Redefining Travel & Services in Syria'}
          </motion.h2>
          <motion.p 
            initial={{ opacity: 0, y: 10 }}
            whileInView={{ opacity: 1, y: 0 }}
            transition={{ delay: 0.2 }}
            className="text-gray-500 text-lg"
          >
            {language === 'ar' 
              ? 'نحن نجمع بين التكنولوجيا الحديثة والضيافة السورية الأصيلة لنقدم لك تجربة لا تُنسى.' 
              : 'We combine modern technology with authentic Syrian hospitality to offer you an unforgettable experience.'}
          </motion.p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-8">
          {features.map((feature, index) => (
            <motion.div
              key={index}
              initial={{ opacity: 0, y: 20 }}
              whileInView={{ opacity: 1, y: 0 }}
              transition={{ delay: index * 0.1 }}
              className="p-8 rounded-3xl border border-gray-100 hover:border-emerald-100 hover:shadow-xl hover:shadow-emerald-500/5 transition-all group"
            >
              <div className={`w-14 h-14 ${feature.color} rounded-2xl flex items-center justify-center mb-6 group-hover:scale-110 transition-transform`}>
                <feature.icon className="w-7 h-7" />
              </div>
              <h3 className="text-xl font-bold text-gray-900 mb-3">{feature.title}</h3>
              <p className="text-gray-500 leading-relaxed text-sm">
                {feature.description}
              </p>
            </motion.div>
          ))}
        </div>

        <div className="mt-20 grid grid-cols-1 md:grid-cols-3 gap-8 border-t border-gray-100 pt-16">
          <div className="text-center">
            <div className="text-4xl font-black text-emerald-600 mb-2">+10,000</div>
            <div className="text-gray-500 font-medium">{language === 'ar' ? 'مستخدم سعيد' : 'Happy Users'}</div>
          </div>
          <div className="text-center">
            <div className="text-4xl font-black text-emerald-600 mb-2">+500</div>
            <div className="text-gray-500 font-medium">{language === 'ar' ? 'مزود خدمة موثق' : 'Verified Providers'}</div>
          </div>
          <div className="text-center">
            <div className="text-4xl font-black text-emerald-600 mb-2">4.9/5</div>
            <div className="text-gray-500 font-medium">{language === 'ar' ? 'تقييم متوسط' : 'Average Rating'}</div>
          </div>
        </div>
      </div>
    </section>
  );
}
